package mahilo.application.service

import cats.effect.IO
import mahilo.application.port.in.{AgentStatus, ObservationQuery}
import mahilo.application.port.out.{MessageQuery, MessageStore, MetricsSnapshot, Telemetry}
import mahilo.domain.AgentName
import mahilo.domain.messaging.{Envelope, StoredMessage}
import mahilo.domain.policy.{Policy, PolicyViolation}

/** Hiện thực port vào `ObservationQuery`: chỉ đọc, chuyển tiếp xuống các port ra. */
final class ObservationService(
    directory: AgentDirectory,
    store: MessageStore,
    telemetry: Telemetry,
    policyEngine: PolicyEngine
) extends ObservationQuery:

  def agents: IO[List[AgentStatus]] =
    directory.activeNames.map { active =>
      directory.specs.map(s => AgentStatus(s.name, s.kind, s.shortDescription, active.contains(s.name)))
    }

  def messages(q: MessageQuery): IO[List[StoredMessage]]                      = store.query(q)
  def conversation(a: AgentName, b: AgentName, limit: Int): IO[List[Envelope]] = store.conversation(a, b, limit)
  def metrics(agent: Option[AgentName]): IO[MetricsSnapshot]                   = telemetry.snapshot(agent)
  def policies: IO[List[Policy]]                                               = policyEngine.current.map(_.all)
  def violations(limit: Int, policyName: Option[String]): IO[List[PolicyViolation]] =
    policyEngine.violations(limit, policyName)
