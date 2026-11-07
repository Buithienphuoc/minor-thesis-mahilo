package mahilo.bootstrap

import scala.concurrent.duration.*
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.all.*
import mahilo.adapters.langchain4j.{ChatModelFromLlmClient, LangGraph4jEngine}
import mahilo.adapters.local.*
import mahilo.application.{Mahilo, MahiloPorts}
import mahilo.application.port.in.ChatOutcome
import mahilo.application.port.out.{AgentEngine, MessageQuery}
import mahilo.application.service.AgentDefinition
import mahilo.bootstrap.templates.CentralizedTemplates
import mahilo.domain.AgentName

/** Chạy trọn kịch bản Dispatcher ↔ Plumber NGAY TRONG TERMINAL, không cần API key, không
  * cần server. Đây là chỗ tốt nhất để bắt đầu đọc code: đặt breakpoint và đi theo luồng.
  *
  *   sbt "bootstrap/runMain mahilo.bootstrap.DemoMain"
  *   sbt "bootstrap/runMain mahilo.bootstrap.DemoMain --langgraph4j"   # plumber chạy bằng LangGraph4j
  */
object DemoMain extends IOApp:

  private val dispatcher = CentralizedTemplates.dispatcher.name
  private val plumber    = CentralizedTemplates.plumber.name

  def run(args: List[String]): IO[ExitCode] =
    val useLangGraph = args.contains("--langgraph4j")
    resources(useLangGraph).use { (mahilo, human) =>
      for
        _ <- IO.println(s"=== Mahilo-Scala demo (plumber engine: ${if useLangGraph then "LangGraph4j" else "vòng lặp gốc"}) ===\n")

        _ <- IO.println("👤 Khách → dispatcher: Toilet nhà tôi bị tắc")
        r <- mahilo.chat.chat(dispatcher, "Toilet nhà tôi bị tắc")
        _ <- printReply(dispatcher, r)

        _ <- IO.println("\n… dispatcher đã gửi tin vào hàng đợi; worker nền giao cho plumber …")
        _ <- awaitDelivered(human, count = 1)

        _ <- IO.println("\n🔧 Thợ thật → plumber: Chiều nay 3h tôi đến được")
        r <- mahilo.chat.chat(plumber, "Chiều nay 3h tôi đến được")
        _ <- printReply(plumber, r)
        _ <- awaitDelivered(human, count = 2)

        _  <- IO.println("\n=== Hàng đợi liên agent (GET /messages) ===")
        ms <- mahilo.observe.messages(MessageQuery())
        _  <- ms.reverse.traverse_(m =>
                IO.println(f"  ${m.envelope.sender.value}%-10s → ${m.envelope.recipient.value}%-10s [${m.state.label}] ${m.envelope.payload}")
              )
        mt <- mahilo.observe.metrics(None)
        _  <- IO.println("\n=== Metrics (GET /metrics) ===")
        _  <- mt.counters.toList.sortBy(_._1).traverse_((k, v) => IO.println(s"  $k = $v"))
      yield ExitCode.Success
    }

  private def resources(useLangGraph: Boolean): Resource[IO, (Mahilo, LocalHumanGateway)] =
    for
      store     <- Resource.eval(InMemoryMessageStore.make)
      sessions  <- Resource.eval(InMemorySessionRepository.make)
      telemetry <- Resource.eval(InMemoryTelemetry.make)
      human     <- Resource.eval(LocalHumanGateway.make(connected = Set(dispatcher, plumber)))
      // Có cờ --langgraph4j thì plumber chạy qua StateGraph của LangGraph4j, với
      // ChatModel (LangChain4j) bọc quanh chính "bộ não" giả lập.
      plumberEngine <-
        if useLangGraph then ChatModelFromLlmClient.resource(DemoBrain.llm).flatMap(LangGraph4jEngine.resource(_)).map(e => Option[AgentEngine](e))
        else Resource.pure[IO, Option[AgentEngine]](None)
      agents = List(
        AgentDefinition(CentralizedTemplates.dispatcher),
        AgentDefinition(CentralizedTemplates.plumber, engine = plumberEngine),
        AgentDefinition(CentralizedTemplates.moldSpecialist)
      )
      ports  = MahiloPorts(DemoBrain.llm, store, sessions, human, telemetry, HmacEnvelopeSigner("demo-secret"))
      mahilo <- Mahilo.resource(agents, startActive = List(dispatcher), ports)
    yield (mahilo, human)

  private def printReply(agent: AgentName, outcome: ChatOutcome): IO[Unit] = outcome match
    case ChatOutcome.Replied(text, active) =>
      IO.println(s"🤖 ${agent.value} trả lời: $text   (agent khác đang active: ${active.map(_.value).mkString(", ")})")
    case other => IO.println(s"⚠️  $other")

  /** Chờ tới khi đã có `count` tin được đẩy tới người dùng (tối đa 5 giây). */
  private def awaitDelivered(human: LocalHumanGateway, count: Int): IO[Unit] =
    (human.delivered.map(_.size >= count), IO.sleep(20.millis)).tupled.map(_._1)
      .iterateUntil(identity).void.timeoutTo(5.seconds, IO.println("  (hết thời gian chờ)"))
