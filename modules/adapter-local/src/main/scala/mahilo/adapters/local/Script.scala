package mahilo.adapters.local

import cats.effect.IO
import mahilo.application.port.out.{LlmReply, LlmRequest}
import mahilo.domain.conversation.{ChatMessage, ToolCall}

/** Vài hàm giúp viết kịch bản cho [[ScriptedLlmClient]] dễ đọc. */
object Script:
  /** Tên agent đang "suy nghĩ" (đọc từ system prompt: "... and name <x> in a multi-agent system"). */
  def agentName(req: LlmRequest): Option[String] =
    req.messages.collect { case ChatMessage.System(c) => c }.lastOption.flatMap { c =>
      """and name (\S+) in a multi-agent""".r.findFirstMatchIn(c).map(_.group(1))
    }

  /** Tin cuối cùng của lượt hiện tại. */
  def last(req: LlmRequest): Option[ChatMessage] = req.messages.lastOption

  /** Nội dung tin `User` gần nhất (tin vừa tới trong lượt này). */
  def lastUserText(req: LlmRequest): String =
    req.messages.collect { case ChatMessage.User(c) => c }.lastOption.getOrElse("")

  def say(text: String): IO[LlmReply] = IO.pure(LlmReply(Some(text)))

  private val counter = java.util.concurrent.atomic.AtomicInteger(0)
  def call(tool: String, args: (String, String)*): IO[LlmReply] =
    val id   = s"call_${counter.incrementAndGet()}"
    val json = args.map((k, v) => s"\"$k\":\"${v.replace("\"", "\\\"")}\"").mkString("{", ",", "}")
    IO.pure(LlmReply(None, List(ToolCall(id, tool, args.toMap, json))))
