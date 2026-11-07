package mahilo.domain.messaging

/** Trạng thái của một tin trong hàng đợi.
  *
  * Python lưu chuỗi "pending"/"processed"/"failed" cộng một cột `retry_count` riêng.
  * Ở đây số lần thử nằm luôn trong trạng thái, nên không thể có tổ hợp vô nghĩa.
  */
enum DeliveryState:
  case Pending(retries: Int)
  case Processed
  case Failed(retries: Int)

  def label: String = this match
    case Pending(_)  => "pending"
    case Processed   => "processed"
    case Failed(_)   => "failed"

  def retryCount: Int = this match
    case Pending(n) => n
    case Processed  => 0
    case Failed(n)  => n

object DeliveryState:
  val New: DeliveryState = Pending(0)

/** Một tin đã lưu: phong bì + trạng thái. */
final case class StoredMessage(envelope: Envelope, state: DeliveryState)

/** Quy tắc thử lại khi xử lý tin thất bại. Python: `MessageBroker.handle_failure`, `MAX_RETRIES = 3`.
  *
  * Đây là hàm thuần: nhận trạng thái hiện tại, trả trạng thái mới. Việc ghi xuống DB và
  * gửi tin ERROR là chuyện của tầng application.
  */
final case class RetryPolicy(maxRetries: Int = 3):
  def afterFailure(current: DeliveryState): DeliveryState =
    val attempt = current.retryCount + 1
    if attempt <= maxRetries then DeliveryState.Pending(attempt)
    else DeliveryState.Failed(attempt)
