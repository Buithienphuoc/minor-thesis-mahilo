package mahilo.application.port.out

import cats.effect.IO
import java.time.Instant
import mahilo.domain.{AgentName, MessageId}
import mahilo.domain.messaging.{DeliveryState, Envelope, StoredMessage}

/** Bộ lọc cho API quan sát `/messages`. Python: tham số của `get_messages(...)`. */
final case class MessageQuery(
    sender: Option[AgentName] = None,
    recipient: Option[AgentName] = None,
    from: Option[Instant] = None,
    to: Option[Instant] = None,
    limit: Int = 100
)

/** PORT RA: nơi lưu hàng đợi tin liên agent. Python: `class MessageStore(ABC)`.
  *
  * Quy ước thứ tự được ghi rõ ở từng hàm (Python không nói rõ, và chính điều đó gây ra
  * lỗi ở policy anti_loop).
  */
trait MessageStore:
  def save(envelope: Envelope, state: DeliveryState = DeliveryState.New): IO[Unit]
  def get(id: MessageId): IO[Option[StoredMessage]]
  def updateState(id: MessageId, state: DeliveryState): IO[Unit]

  /** Tin đang chờ của một agent, CŨ → MỚI (xử lý theo thứ tự đến). */
  def pending(recipient: AgentName): IO[List[StoredMessage]]

  /** Những agent đang có tin chờ (dùng lúc khởi động lại để "đánh thức" chúng). */
  def recipientsWithPending: IO[Set[AgentName]]

  /** `limit` tin gần nhất giữa hai agent theo cả hai chiều, trả về CŨ → MỚI. */
  def conversation(a: AgentName, b: AgentName, limit: Int): IO[List[Envelope]]

  /** Cho API quan sát, MỚI → CŨ. */
  def query(q: MessageQuery): IO[List[StoredMessage]]
