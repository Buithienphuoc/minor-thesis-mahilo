package mahilo.application.service

import scala.concurrent.duration.FiniteDuration
import cats.syntax.all.*

/** Tham số chạy của runtime.
  * @param contextWindow     số tin cuối của mỗi agent khác đưa vào "Other Conversations" (Python: 7)
  * @param maxToolIterations số vòng gọi tool tối đa trong một lượt (Python: không giới hạn)
  * @param retryDelay        chờ bao lâu trước khi thử lại tin bị lỗi
  */
final case class RuntimeConfig(contextWindow: Int, maxToolIterations: Int, retryDelay: FiniteDuration)
