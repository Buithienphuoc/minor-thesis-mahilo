package mahilo.domain.messaging

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
