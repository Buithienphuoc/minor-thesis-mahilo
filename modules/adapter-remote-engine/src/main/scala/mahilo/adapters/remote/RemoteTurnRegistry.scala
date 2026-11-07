package mahilo.adapters.remote

import java.util.UUID
import cats.effect.{IO, Ref, Resource}
import mahilo.domain.conversation.ToolCall

/** Sổ các lượt đang chạy ở engine ngoài. Engine gọi callback với `turn_id`; sổ này cho
  * biết lượt đó thuộc agent nào và chạy tool bằng hàm nào.
  *
  * Một lượt chỉ có mặt trong sổ khi request `/v1/turns` tương ứng còn đang chạy, nên
  * engine không thể gọi tool "ngoài giờ" (callback trễ sẽ nhận 404).
  */
final class RemoteTurnRegistry private (turns: Ref[IO, Map[String, ToolCall => IO[String]]]):

  def open(execute: ToolCall => IO[String]): Resource[IO, String] =
    Resource.make(IO(UUID.randomUUID().toString).flatTap(id => turns.update(_.updated(id, execute))))(id =>
      turns.update(_ - id)
    )

  def lookup(turnId: String): IO[Option[ToolCall => IO[String]]] = turns.get.map(_.get(turnId))

object RemoteTurnRegistry:
  def make: IO[RemoteTurnRegistry] = Ref.of[IO, Map[String, ToolCall => IO[String]]](Map.empty).map(RemoteTurnRegistry(_))
