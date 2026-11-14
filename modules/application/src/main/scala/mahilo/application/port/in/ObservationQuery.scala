package mahilo.application.port.in

import cats.effect.IO
import mahilo.domain.AgentName
import mahilo.domain.messaging.{Envelope, StoredMessage}
import mahilo.domain.policy.{Policy, PolicyViolation}
import mahilo.application.port.out.{MessageQuery, MetricsSnapshot}

/** Các truy vấn chỉ đọc cho người vận hành. Python: các route REST trong `server.py`. */
trait ObservationQuery:
  def agents: IO[List[AgentStatus]]
  def messages(q: MessageQuery): IO[List[StoredMessage]]
  def conversation(a: AgentName, b: AgentName, limit: Int): IO[List[Envelope]]
  def metrics(agent: Option[AgentName]): IO[MetricsSnapshot]
  def policies: IO[List[Policy]]
  def violations(limit: Int, policyName: Option[String]): IO[List[PolicyViolation]]
