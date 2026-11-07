package mahilo.adapters.http

import java.util.UUID
import cats.effect.{IO, Ref, Resource}
import cats.effect.std.Queue
import cats.syntax.all.*
import mahilo.application.port.out.HumanGateway
import mahilo.domain.AgentName

/** ADAPTER cho port ra `HumanGateway`: giữ danh sách WebSocket đang mở của từng agent.
  * Python: `ServerManager.websocket_connections: Dict[agent, Dict[connId, WebSocket]]`.
  *
  * Mỗi kết nối có một hàng đợi chuỗi; route WebSocket đọc hàng đợi này để đẩy xuống client.
  */
final class WebSocketHumanGateway private (connections: Ref[IO, Map[AgentName, Map[UUID, Queue[IO, String]]]])
    extends HumanGateway:

  def send(agent: AgentName, text: String): IO[Int] =
    connections.get.flatMap { all =>
      val queues = all.getOrElse(agent, Map.empty).values.toList
      queues.traverse_(_.offer(text)).as(queues.size)
    }

  /** Đăng ký một kết nối mới; tự hủy đăng ký khi kết nối đóng. */
  def connect(agent: AgentName): Resource[IO, Queue[IO, String]] =
    Resource.make(
      for
        id    <- IO(UUID.randomUUID())
        queue <- Queue.unbounded[IO, String]
        _     <- connections.update(m => m.updated(agent, m.getOrElse(agent, Map.empty).updated(id, queue)))
      yield (id, queue)
    ) { (id, _) =>
      connections.update { m =>
        val left = m.getOrElse(agent, Map.empty) - id
        if left.isEmpty then m - agent else m.updated(agent, left)
      }
    }.map(_._2)

object WebSocketHumanGateway:
  def make: IO[WebSocketHumanGateway] =
    Ref.of[IO, Map[AgentName, Map[UUID, Queue[IO, String]]]](Map.empty).map(WebSocketHumanGateway(_))
