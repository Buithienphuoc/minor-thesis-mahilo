package mahilo.application

import cats.effect.{IO, Resource}
import cats.effect.std.{Mutex, Queue, Supervisor}
import cats.syntax.all.*
import mahilo.application.port.in.*
import mahilo.application.service.*
import mahilo.domain.AgentName
import mahilo.domain.policy.Policy
import mahilo.domain.telemetry.TelemetryEvent

/** Mặt tiền của lõi: gom các port vào để adapter vào (HTTP, CLI, test) dùng.
  * Python: `AgentManager` (vừa là registry, vừa dựng broker/store/policy/telemetry).
  */
final class Mahilo(
    val chat: ChatUseCase,
    val inbox: InboxUseCase,
    val admin: AdminUseCase,
    val observe: ObservationQuery
)

object Mahilo:

  /** Dựng toàn bộ lõi và chạy worker nền. Worker tự dừng khi `Resource` được giải phóng.
    *
    * @param agents       các agent đăng ký
    * @param startActive  các agent bật sẵn (Python: `dispatcher.activate()` trong run_server.py)
    */
  def resource(
      agents: List[AgentDefinition],
      startActive: List[AgentName],
      ports: MahiloPorts,
      config: MahiloConfig = MahiloConfig()
  ): Resource[IO, Mahilo] =
    for
      supervisor <- Supervisor[IO]
      queue      <- Resource.eval(Queue.unbounded[IO, AgentName])
      directory  <- Resource.eval(AgentDirectory.make(agents))
      locks      <- Resource.eval(agents.traverse(a => Mutex[IO].map(a.spec.name -> _)).map(_.toMap))
      policies   <- Resource.eval(PolicyEngine.make(config.policies, ports.llm, config.policyJudgeModel))
      wakeUp      = (a: AgentName) => queue.offer(a)
      broker      = MessageBroker(ports.store, policies, ports.signer, ports.telemetry, config.retry, config.policyHistorySize, wakeUp)
      runtime     = AgentRuntime(
                      directory, ports.sessions, broker, ports.human, ports.telemetry,
                      defaultEngine = ToolLoopEngine(ports.llm, ports.telemetry),
                      locks, supervisor, wakeUp,
                      RuntimeConfig(config.contextWindow, config.maxToolIterations, config.retryDelay)
                    )
      _          <- Resource.eval(agents.traverse_(a => ports.telemetry.record(TelemetryEvent.AgentRegistered(a.spec.name))))
      _          <- Resource.eval(startActive.traverse_(runtime.activate))
      // Khởi động lại sau sự cố: đánh thức agent nào còn tin pending trong store.
      _          <- Resource.eval(ports.store.recipientsWithPending.flatMap(_.toList.traverse_(wakeUp)))
      _          <- DeliveryWorker.run(queue, runtime).background
      admin       = new AdminUseCase:
                      def activate(agent: AgentName): IO[Boolean] = runtime.activate(agent)
                      def enablePolicy(name: String): IO[Unit]    = policies.update(_.setEnabled(name, true))
                      def disablePolicy(name: String): IO[Unit]   = policies.update(_.setEnabled(name, false))
                      def addPolicy(policy: Policy): IO[Unit]     = policies.update(_.add(policy))
    yield Mahilo(runtime, runtime, admin, ObservationService(directory, ports.store, ports.telemetry, policies))
