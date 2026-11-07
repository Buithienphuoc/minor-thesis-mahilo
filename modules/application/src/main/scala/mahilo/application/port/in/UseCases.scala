package mahilo.application.port.in

import cats.effect.IO
import mahilo.domain.AgentName
import mahilo.domain.messaging.{Envelope, StoredMessage}
import mahilo.domain.policy.{Policy, PolicyViolation}
import mahilo.application.port.out.{MessageQuery, MetricsSnapshot}

// =====================================================================
//  PORT VÀO (driving ports): những gì thế giới bên ngoài được phép yêu cầu lõi làm.
//  Adapter HTTP/WebSocket, CLI, test... chỉ nói chuyện với lõi qua các trait này.
// =====================================================================

/** Kết quả một lượt chat trực tiếp. */
enum ChatOutcome:
  case Replied(text: String, activeAgents: List[AgentName])
  case AgentNotFound(agent: AgentName)
  case AgentInactive(agent: AgentName)

/** Người dùng nhắn cho agent của mình. Python: `BaseAgent.process_chat_message`. */
trait ChatUseCase:
  def chat(agent: AgentName, text: String): IO[ChatOutcome]

/** Agent xử lý hộp thư (tin từ agent khác). Python: `BaseAgent.process_queue_message`.
  * Bình thường do `DeliveryWorker` gọi; để public để test/CLI có thể gọi tay.
  */
trait InboxUseCase:
  /** @return số tin đã xử lý xong (thành công) */
  def processInbox(agent: AgentName): IO[Int]

/** Quản trị lúc chạy. */
trait AdminUseCase:
  /** Python: `agent.activate()`. Trả false nếu agent không tồn tại. */
  def activate(agent: AgentName): IO[Boolean]
  def enablePolicy(name: String): IO[Unit]
  def disablePolicy(name: String): IO[Unit]
  def addPolicy(policy: Policy): IO[Unit]

final case class AgentStatus(name: AgentName, kind: String, shortDescription: String, active: Boolean)

/** Các truy vấn chỉ đọc cho người vận hành. Python: các route REST trong `server.py`. */
trait ObservationQuery:
  def agents: IO[List[AgentStatus]]
  def messages(q: MessageQuery): IO[List[StoredMessage]]
  def conversation(a: AgentName, b: AgentName, limit: Int): IO[List[Envelope]]
  def metrics(agent: Option[AgentName]): IO[MetricsSnapshot]
  def policies: IO[List[Policy]]
  def violations(limit: Int, policyName: Option[String]): IO[List[PolicyViolation]]
