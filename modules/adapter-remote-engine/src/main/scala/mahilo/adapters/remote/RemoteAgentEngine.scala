package mahilo.adapters.remote

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import cats.effect.IO
import mahilo.application.port.out.{AgentEngine, AgentTurn, TurnResult}

/** ADAPTER cho port `AgentEngine`: giao lượt của agent cho một engine chạy ở tiến trình
  * khác (vd service Python PydanticAI) qua REST.
  *
  * Python: `mahilo/integrations/pydanticai/agent.py` chạy PydanticAI ngay trong tiến trình
  * Mahilo. Ở đây PydanticAI là một service riêng; với lõi, nó chỉ là thêm một hiện thực
  * của `AgentEngine`, ngang hàng với `ToolLoopEngine` và `LangGraph4jEngine`.
  *
  * @param engineUrl   gốc URL của engine, vd http://localhost:8001
  * @param callbackUrl gốc URL mà ENGINE dùng để gọi ngược về Mahilo, vd http://localhost:8000
  *                    (route nằm ở [[RemoteEngineRoutes]])
  */
final class RemoteAgentEngine(
    engineUrl: String,
    callbackUrl: String,
    registry: RemoteTurnRegistry,
    timeout: Duration = Duration.ofSeconds(120)
) extends AgentEngine:

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

  def run(turn: AgentTurn): IO[TurnResult] =
    // Lượt chỉ được đăng ký trong lúc request còn chạy: xong (hoặc lỗi) là gỡ ra.
    registry.open(turn.executeTool).use { turnId =>
      val body = RemoteEngineJson.turnRequest(turnId, turn, s"${callbackUrl.stripSuffix("/")}/engine/turns/$turnId/tools")
      val req = HttpRequest
        .newBuilder(URI.create(s"${engineUrl.stripSuffix("/")}/v1/turns"))
        .timeout(timeout)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body.noSpaces))
        .build()
      IO.fromCompletableFuture(IO(http.sendAsync(req, HttpResponse.BodyHandlers.ofString()))).flatMap { res =>
        if res.statusCode() / 100 != 2 then
          IO.raiseError(RuntimeException(s"Remote engine HTTP ${res.statusCode()}: ${res.body().take(500)}"))
        else IO.fromEither(RemoteEngineJson.turnResult(res.body()).left.map(RuntimeException(_)))
      }
    }
