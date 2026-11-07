package mahilo.adapters.remote

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import org.http4s.HttpRoutes
import org.http4s.circe.*
import org.http4s.dsl.io.*

/** ADAPTER VÀO cho callback của engine ngoài: `POST /engine/turns/{turnId}/tools/{tool}`.
  *
  * Route này chạy tool qua đúng hàm `executeTool` mà lõi đưa cho lượt đó, nên
  * `chat_with_agent` vẫn qua broker + policy, `contact_human` vẫn qua `HumanGateway`.
  * Bootstrap gắn route này vào server HTTP chung.
  */
final class RemoteEngineRoutes(registry: RemoteTurnRegistry):
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "engine" / "turns" / turnId / "tools" / toolName =>
      registry.lookup(turnId).flatMap {
        case None => NotFound(Json.obj("error" -> s"Unknown or finished turn '$turnId'".asJson))
        case Some(execute) =>
          req.as[Json].flatMap(body => execute(RemoteEngineJson.invocation(toolName, body)))
            .flatMap(result => Ok(Json.obj("result" -> result.asJson)))
      }
  }
