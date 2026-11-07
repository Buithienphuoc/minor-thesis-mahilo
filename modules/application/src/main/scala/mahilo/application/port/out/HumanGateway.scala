package mahilo.application.port.out

import cats.effect.IO
import mahilo.domain.AgentName

/** PORT RA: đẩy một tin tới (các) người dùng đang kết nối với agent.
  *
  * Python: `contact_human` nhận thẳng list WebSocket của FastAPI làm tham số, nên lõi
  * agent dính chặt vào web framework. Ở đây lõi chỉ nói "gửi câu này cho người của
  * agent X"; adapter HTTP biết X đang có những WebSocket nào.
  *
  * @return số kết nối đã nhận được tin (0 = không có ai đang nghe)
  */
trait HumanGateway:
  def send(agent: AgentName, text: String): IO[Int]
