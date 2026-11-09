package mahilo.bootstrap

import scala.concurrent.duration.*
import cats.effect.{IO, Resource}
import mahilo.adapters.local.*
import mahilo.application.{Mahilo, MahiloConfig, MahiloPorts}
import mahilo.application.port.out.{EnvelopeSigner, LlmClient, MessageStore}
import mahilo.application.service.AgentDefinition
import mahilo.bootstrap.templates.CentralizedTemplates
import mahilo.domain.AgentName

/** Dựng một hệ Mahilo hoàn toàn trong RAM cho test. */
final case class TestSystem(
    mahilo: Mahilo,
    store: InMemoryMessageStore,
    human: LocalHumanGateway,
    telemetry: InMemoryTelemetry,
    sessions: InMemorySessionRepository
)

object TestKit:
  val dispatcher: AgentName = CentralizedTemplates.dispatcher.name
  val plumber: AgentName    = CentralizedTemplates.plumber.name

  def system(
      llm: LlmClient = DemoBrain.llm,
      agents: List[AgentDefinition] = CentralizedTemplates.all.map(AgentDefinition(_)),
      config: MahiloConfig = MahiloConfig(retryDelay = 10.millis),
      signer: EnvelopeSigner = HmacEnvelopeSigner("test"),
      preload: MessageStore => IO[Unit] = _ => IO.unit,
      startActive: List[AgentName] = List(dispatcher),
      humans: Set[AgentName] = Set(dispatcher, plumber)
  ): Resource[IO, TestSystem] =
    for
      store     <- Resource.eval(InMemoryMessageStore.make)
      _         <- Resource.eval(preload(store))
      sessions  <- Resource.eval(InMemorySessionRepository.make)
      telemetry <- Resource.eval(InMemoryTelemetry.make)
      human     <- Resource.eval(LocalHumanGateway.make(humans, echo = false))
      mahilo    <- Mahilo.resource(agents, startActive, MahiloPorts(llm, store, sessions, human, telemetry, signer), config)
    yield TestSystem(mahilo, store, human, telemetry, sessions)

  /** Chờ điều kiện đúng (worker chạy nền nên kết quả đến không đồng bộ). */
  def eventually[A](io: IO[A])(ok: A => Boolean, timeout: FiniteDuration = 5.seconds): IO[A] =
    (io <* IO.sleep(10.millis)).iterateUntil(ok).timeout(timeout)
