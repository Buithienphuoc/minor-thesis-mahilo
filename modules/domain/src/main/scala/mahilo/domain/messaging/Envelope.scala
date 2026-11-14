package mahilo.domain.messaging

import java.time.Instant
import mahilo.domain.{AgentName, MessageId}

/** "Phong bì" bọc một tin nhắn giữa hai agent. Python: `@dataclass MessageEnvelope`.
  *
  * Phong bì là bất biến. Trạng thái giao nhận (pending/processed/failed) KHÔNG nằm ở đây
  * mà ở [[DeliveryState]], vì đó là thông tin của hàng đợi chứ không phải của tin.
  */
final case class Envelope(
    id: MessageId,
    sender: AgentName,
    recipient: AgentName,
    kind: MessageKind,
    payload: String,
    timestamp: Instant,
    correlationId: Option[String] = None,
    replyTo: Option[MessageId] = None,
    signature: Option[String] = None
):
  /** Chuỗi dùng để ký. Python ký JWT của `{message_id, payload}`. */
  def signingInput: String = s"${id.value}\u0000$payload"
