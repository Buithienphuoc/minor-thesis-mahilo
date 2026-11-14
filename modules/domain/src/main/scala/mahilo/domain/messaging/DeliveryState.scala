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
