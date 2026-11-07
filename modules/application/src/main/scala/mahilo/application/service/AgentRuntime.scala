package mahilo.application.service

import scala.concurrent.duration.FiniteDuration
import cats.effect.IO
import cats.effect.std.{Console, Mutex, Supervisor}
import cats.syntax.all.*
import mahilo.application.port.in.*
import mahilo.application.port.out.*
import mahilo.domain.AgentName
import mahilo.domain.agent.AgentSpec
import mahilo.domain.conversation.{ChatMessage, Session, ToolCall}
import mahilo.domain.messaging.{DeliveryState, StoredMessage}
import mahilo.domain.prompt.PromptBuilder
import mahilo.domain.telemetry.TelemetryEvent
import mahilo.domain.tool.{BuiltinTools, ToolSpec}

/** Tham số chạy của runtime.
  * @param contextWindow     số tin cuối của mỗi agent khác đưa vào "Other Conversations" (Python: 7)
  * @param maxToolIterations số vòng gọi tool tối đa trong một lượt (Python: không giới hạn)
  * @param retryDelay        chờ bao lâu trước khi thử lại tin bị lỗi
  */
final case class RuntimeConfig(contextWindow: Int, maxToolIterations: Int, retryDelay: FiniteDuration)

/** Trái tim của Mahilo: chạy một lượt của agent cho chat trực tiếp và cho hộp thư.
  * Python: phần lớn `BaseAgent` (agent.py, 917 dòng).
  *
  * Mỗi agent có một `Mutex`, nên chat trực tiếp và xử lý hộp thư của CÙNG một agent
  * không bao giờ chạy song song (Python dùng chung list `messages` mà không khóa).
  */
final class AgentRuntime(
    directory: AgentDirectory,
    sessions: SessionRepository,
    broker: MessageBroker,
    human: HumanGateway,
    telemetry: Telemetry,
    defaultEngine: AgentEngine,
    locks: Map[AgentName, Mutex[IO]],
    supervisor: Supervisor[IO],
    wakeUp: AgentName => IO[Unit],
    config: RuntimeConfig
) extends ChatUseCase
    with InboxUseCase:

  // ------------------------------------------------------------------
  //  Chat trực tiếp: người dùng → agent
  // ------------------------------------------------------------------

  def chat(agent: AgentName, text: String): IO[ChatOutcome] =
    directory.get(agent) match
      case None => IO.pure(ChatOutcome.AgentNotFound(agent))
      case Some(defn) =>
        directory.isActive(agent).flatMap {
          case false => IO.pure(ChatOutcome.AgentInactive(agent))
          case true  => locks(agent).lock.surround(chatLocked(defn, text))
        }

  private def chatLocked(defn: AgentDefinition, text: String): IO[ChatOutcome] =
    val spec = defn.spec
    for
      session <- sessions.load(spec.name)
      context <- otherConversations(spec.name)
      // Chat trực tiếp KHÔNG có contact_human: câu trả lời cuối đã về thẳng người dùng.
      tools   = chatWithAgentSpec(spec) :: defn.tools.map(_.spec)
      input   = session.messages.toList ++ List(
                  ChatMessage.System(systemPrompt(spec)),
                  ChatMessage.User(PromptBuilder.directUserTurn(context, text))
                )
      result  <- engineFor(defn).run(AgentTurn(spec, input, tools, executeTool(defn), config.maxToolIterations))
      // Session chỉ giữ tin "thật" của người dùng, không giữ system prompt và ngữ cảnh chéo.
      _       <- sessions.save((session :+ ChatMessage.User(text)).append(result.newMessages))
      others  <- directory.activeNames.map(_.filterNot(_ == spec.name).toList.sortBy(_.value))
    yield ChatOutcome.Replied(result.reply.getOrElse(""), others)

  // ------------------------------------------------------------------
  //  Hộp thư: agent khác → agent
  // ------------------------------------------------------------------

  def processInbox(agent: AgentName): IO[Int] =
    directory.get(agent) match
      case None => IO.pure(0)
      case Some(defn) =>
        directory.isActive(agent).ifM(
          locks(agent).lock.surround(
            broker.pending(agent).flatMap(_.traverse(processOne(defn, _))).map(_.count(identity))
          ),
          IO.pure(0)
        )

  private def processOne(defn: AgentDefinition, msg: StoredMessage): IO[Boolean] =
    val env = msg.envelope
    if !broker.verify(env) then
      Console[IO].errorln(s"[inbox] tin ${env.id.value} sai chữ ký, bỏ") *> broker.rejectForgery(msg).as(false)
    else
      handleEnvelope(defn, PromptBuilder.pendingMessage(env)).attempt.flatMap {
        case Right(_) => broker.acknowledge(env).as(true)
        case Left(err) =>
          Console[IO].errorln(s"[inbox] lỗi xử lý ${env.id.value}: ${err.getMessage}") *>
            broker.fail(msg, err).flatMap {
              case DeliveryState.Pending(_) => scheduleRetry(defn.spec.name).as(false)
              case _                        => IO.pure(false)
            }
      }

  private def handleEnvelope(defn: AgentDefinition, queueText: String): IO[Unit] =
    val spec = defn.spec
    for
      session <- sessions.load(spec.name)
      tools    = chatWithAgentSpec(spec) :: BuiltinTools.contactHuman :: defn.tools.map(_.spec)
      input    = session.messages.toList ++ List(ChatMessage.System(systemPrompt(spec)), ChatMessage.User(queueText))
      result  <- engineFor(defn).run(AgentTurn(spec, input, tools, executeTool(defn), config.maxToolIterations))
      _       <- sessions.save((session :+ ChatMessage.User(queueText)).append(result.newMessages))
    yield ()

  private def scheduleRetry(agent: AgentName): IO[Unit] =
    supervisor.supervise(IO.sleep(config.retryDelay) *> wakeUp(agent)).void

  // ------------------------------------------------------------------
  //  Thực thi tool
  // ------------------------------------------------------------------

  /** Chạy một tool_call. Không bao giờ ném lỗi: lỗi được trả về dạng văn bản cho LLM. */
  private def executeTool(defn: AgentDefinition)(call: ToolCall): IO[String] =
    val self = defn.spec
    val run: IO[String] = call.name match
      case BuiltinTools.ChatWithAgent => chatWithAgent(self, call.arguments)
      case BuiltinTools.ContactHuman  => contactHuman(self, call.arguments)
      case other =>
        defn.tools.find(_.spec.name == other) match
          case None => IO.pure(s"Error: unknown tool '$other'")
          case Some(tool) =>
            tool.spec.missingRequired(call.arguments) match
              case Nil     => tool.run(call.arguments)
              case missing => IO.pure(s"Error: missing required argument(s): ${missing.mkString(", ")}")
    run.attempt.flatMap {
      case Right(out) => telemetry.record(TelemetryEvent.ToolCalled(self.name, call.name, true)).as(out)
      case Left(e) =>
        telemetry.record(TelemetryEvent.ToolCalled(self.name, call.name, false))
          .as(s"Error calling ${call.name}: ${e.getMessage}")
    }

  /** Python: `tools.py::chat_with_agent`. Người gửi = agent đang chạy (không do LLM khai). */
  private def chatWithAgent(self: AgentSpec, args: Map[String, String]): IO[String] =
    (args.get("agent_name").map(AgentName(_)), args.get("question")) match
      case (None, _) | (_, None) => IO.pure("Error: chat_with_agent needs 'agent_name' and 'question'")
      case (Some(target), Some(question)) =>
        directory.get(target) match
          case None                                   => IO.pure(s"Error: Agent '${target.value}' not found")
          case Some(_) if target == self.name         => IO.pure("Error: you cannot send a message to yourself")
          case Some(_) if !self.canContact.allows(target) =>
            IO.pure(s"Error: you are not allowed to contact '${target.value}'")
          case Some(_) =>
            activate(target) *> broker.send(self.name, target, question).map {
              case SendOutcome.Queued(_) =>
                s"Message sent to ${target.value}. They will process it and respond when ready."
              case SendOutcome.Rejected(_, vs) =>
                // Khác Python: báo ngay cho LLM biết tin bị chặn, kèm lý do.
                s"Your message to ${target.value} was rejected due to policy violations:\n" +
                  vs.map(v => s"Policy '${v.policyName}': ${v.reason}").mkString("\n") +
                  "\nPlease modify your message and try again."
            }

  /** Python: `BaseAgent.contact_human`. */
  private def contactHuman(self: AgentSpec, args: Map[String, String]): IO[String] =
    args.get("message") match
      case None => IO.pure("Error: contact_human needs 'message'")
      case Some(text) =>
        human.send(self.name, text).flatTap(n => telemetry.record(TelemetryEvent.HumanContacted(self.name, n))).map {
          case 0 => s"No human is connected to ${self.name.value} right now; the message was not delivered."
          case _ => s"Sent text message to human: $text"
        }

  // ------------------------------------------------------------------
  //  Tiện ích
  // ------------------------------------------------------------------

  /** Bật agent; nếu mới bật thì đánh thức nó để xử lý tin đang chờ. */
  def activate(agent: AgentName): IO[Boolean] =
    directory.get(agent) match
      case None => IO.pure(false)
      case Some(_) =>
        directory.activate(agent).flatMap { fresh =>
          (telemetry.record(TelemetryEvent.AgentActivated(agent)) *> wakeUp(agent)).whenA(fresh)
        }.as(true)

  private def engineFor(defn: AgentDefinition): AgentEngine = defn.engine.getOrElse(defaultEngine)

  private def systemPrompt(spec: AgentSpec): String =
    PromptBuilder.systemPrompt(spec, directory.contactableFor(spec))

  private def chatWithAgentSpec(spec: AgentSpec): ToolSpec =
    BuiltinTools.chatWithAgent(directory.contactableFor(spec).map(a => a.name.value -> a.shortDescription).toMap)

  /** Python: `AgentManager.get_agent_messages(name, 7)` — vài tin cuối của mọi agent active khác. */
  private def otherConversations(self: AgentName): IO[String] =
    directory.activeNames.flatMap { names =>
      names.toList.filterNot(_ == self).sortBy(_.value)
        .traverse(n => sessions.load(n).map((s: Session) => n -> s.lastN(config.contextWindow)))
        .map(PromptBuilder.otherConversations)
    }
