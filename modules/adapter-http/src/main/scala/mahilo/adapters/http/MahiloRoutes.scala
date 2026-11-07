package mahilo.adapters.http

import java.time.Instant
import cats.effect.IO
import fs2.Stream
import io.circe.Json
import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.server.websocket.WebSocketBuilder2
import org.http4s.websocket.WebSocketFrame
import mahilo.application.Mahilo
import mahilo.application.port.in.ChatOutcome
import mahilo.application.port.out.MessageQuery
import mahilo.domain.AgentName

/** ADAPTER VÀO (driving): dịch HTTP/WebSocket thành lời gọi port vào của lõi.
  * Python: các route FastAPI trong `server.py`.
  *
  * Route này không chứa logic nghiệp vụ nào: đọc tham số → gọi use case → in JSON.
  */
final class MahiloRoutes(mahilo: Mahilo, gateway: WebSocketHumanGateway):

  private object SenderQ    extends OptionalQueryParamDecoderMatcher[String]("sender")
  private object RecipientQ extends OptionalQueryParamDecoderMatcher[String]("recipient")
  private object StartQ     extends OptionalQueryParamDecoderMatcher[Double]("start_time")
  private object EndQ       extends OptionalQueryParamDecoderMatcher[Double]("end_time")
  private object LimitQ     extends OptionalQueryParamDecoderMatcher[Int]("limit")
  private object AgentQ     extends OptionalQueryParamDecoderMatcher[String]("agent_id")
  private object PolicyQ    extends OptionalQueryParamDecoderMatcher[String]("policy_name")

  private def epoch(s: Double): Instant = Instant.ofEpochMilli((s * 1000).toLong)

  def routes(ws: WebSocketBuilder2[IO]): HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "health" => Ok(Json.obj("status" -> "ok".asJson))

    case GET -> Root / "agents" =>
      mahilo.observe.agents.flatMap(as => Ok(Json.arr(as.map(JsonViews.agent)*)))

    case POST -> Root / "agents" / name / "activate" =>
      mahilo.admin.activate(AgentName(name)).flatMap {
        case true  => Ok(Json.obj("activated" -> name.asJson))
        case false => NotFound(Json.obj("error" -> s"Agent '$name' is not registered".asJson))
      }

    // Chat qua REST (tiện để thử bằng curl). WebSocket bên dưới làm đúng việc này.
    case req @ POST -> Root / "chat" / name =>
      req.as[String].flatMap(text => mahilo.chat.chat(AgentName(name), text)).flatMap {
        case ChatOutcome.Replied(text, active) =>
          Ok(Json.obj("response" -> text.asJson, "activated_agents" -> active.map(_.value).asJson))
        case ChatOutcome.AgentNotFound(a) => NotFound(Json.obj("error" -> s"Agent '${a.value}' is not registered".asJson))
        case ChatOutcome.AgentInactive(a) => Conflict(Json.obj("error" -> s"Agent ${a.value} is not active.".asJson))
      }

    case GET -> Root / "messages" :? SenderQ(s) +& RecipientQ(r) +& StartQ(from) +& EndQ(to) +& LimitQ(limit) =>
      val q = MessageQuery(s.map(AgentName(_)), r.map(AgentName(_)), from.map(epoch), to.map(epoch), limit.getOrElse(100))
      mahilo.observe.messages(q).flatMap(ms => Ok(Json.arr(ms.map(JsonViews.stored)*)))

    case GET -> Root / "conversations" / a / b :? LimitQ(limit) =>
      mahilo.observe.conversation(AgentName(a), AgentName(b), limit.getOrElse(50))
        .flatMap(es => Ok(Json.arr(es.map(JsonViews.envelope)*)))

    case GET -> Root / "metrics" :? AgentQ(agent) =>
      mahilo.observe.metrics(agent.map(AgentName(_))).flatMap(m => Ok(JsonViews.metrics(m)))

    case GET -> Root / "policies" =>
      mahilo.observe.policies.flatMap(ps => Ok(Json.arr(ps.map(JsonViews.policy)*)))

    case POST -> Root / "policies" / name / "enable"  => mahilo.admin.enablePolicy(name) *> Ok(Json.obj("enabled" -> name.asJson))
    case POST -> Root / "policies" / name / "disable" => mahilo.admin.disablePolicy(name) *> Ok(Json.obj("disabled" -> name.asJson))

    case GET -> Root / "policies" / "violations" :? LimitQ(limit) +& PolicyQ(p) =>
      mahilo.observe.violations(limit.getOrElse(100), p).flatMap(vs => Ok(Json.arr(vs.map(JsonViews.violation)*)))

    // Python: @app.websocket("/ws/{agent_name}")
    case GET -> Root / "ws" / name => websocket(ws, AgentName(name))
  }

  /** Mỗi kết nối: đăng ký với gateway (để nhận tin `contact_human`), rồi với mỗi frame text
    * nhận được thì gọi `ChatUseCase` và đẩy câu trả lời vào cùng hàng đợi gửi đi.
    */
  private def websocket(ws: WebSocketBuilder2[IO], agent: AgentName): IO[Response[IO]] =
    ws.build(
        sendReceive = (in: Stream[IO, WebSocketFrame]) =>
          Stream.resource(gateway.connect(agent)).flatMap { outbox =>
            val replies = in.collect { case WebSocketFrame.Text(text, _) => text }.evalMap { text =>
              mahilo.chat.chat(agent, text).flatMap {
                case ChatOutcome.Replied(reply, _) => outbox.offer(reply)
                case ChatOutcome.AgentNotFound(a)  => outbox.offer(s"Error: Agent '${a.value}' is not registered with the server")
                case ChatOutcome.AgentInactive(a)  => outbox.offer(s"Agent ${a.value} is not active.")
              }
            }
            Stream.fromQueueUnterminated(outbox).map(WebSocketFrame.Text(_)).concurrently(replies)
          }
      )
