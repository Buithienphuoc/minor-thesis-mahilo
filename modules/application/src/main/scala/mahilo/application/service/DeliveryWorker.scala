package mahilo.application.service

import cats.effect.IO
import cats.effect.std.{Console, Queue}
import mahilo.application.port.in.InboxUseCase
import mahilo.domain.AgentName

/** Fiber nền phân phối tin. Python: `ServerManager._handle_inter_agent_communication`,
  * một vòng `while True` quét MỌI agent rồi `sleep(1)`.
  *
  * Ở đây: broker bỏ tên agent nhận vào `queue` ngay khi có tin; worker lấy ra và gọi
  * `processInbox`. Không có việc thì worker ngủ trên `queue.take`, không tốn CPU.
  * Một tên bị bỏ vào nhiều lần cũng không sao: lần sau sẽ thấy hộp thư trống.
  */
object DeliveryWorker:
  def run(queue: Queue[IO, AgentName], inbox: InboxUseCase): IO[Nothing] =
    queue.take
      .flatMap(agent =>
        inbox.processInbox(agent).void.handleErrorWith(e => Console[IO].errorln(s"[worker] ${agent.value}: ${e.getMessage}"))
      )
      .foreverM
