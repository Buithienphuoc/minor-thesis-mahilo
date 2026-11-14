package mahilo.application.port.in

import cats.effect.IO
import mahilo.domain.AgentName

/** Agent xử lý hộp thư (tin từ agent khác). Python: `BaseAgent.process_queue_message`.
  * Bình thường do `DeliveryWorker` gọi; để public để test/CLI có thể gọi tay.
  */
trait InboxUseCase:
  /** @return số tin đã xử lý xong (thành công) */
  def processInbox(agent: AgentName): IO[Int]
