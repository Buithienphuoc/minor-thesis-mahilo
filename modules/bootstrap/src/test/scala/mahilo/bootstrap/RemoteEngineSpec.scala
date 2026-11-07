package mahilo.bootstrap

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.duration.*
import cats.effect.{IO, Resource}
import com.comcast.ip4s.{Host, Port}
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.{HttpRoutes, Method, Request, Uri}
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import mahilo.adapters.http.{HttpServer, WebSocketHumanGateway}
import mahilo.adapters.local.*
import mahilo.adapters.remote.{RemoteAgentEngine, RemoteEngineRoutes, RemoteTurnRegistry}
import mahilo.application.{Mahilo, MahiloConfig, MahiloPorts}
import mahilo.application.port.out.MessageQuery
import mahilo.application.service.AgentDefinition
import mahilo.bootstrap.templates.CentralizedTemplates
import mahilo.domain.conversation.ChatMessage
import mahilo.domain.messaging.DeliveryState

/** Engine ngoài tiến trình qua REST. Engine "giả" ở đây viết bằng http4s và làm đúng
  * những gì service Python PydanticAI làm: nhận lượt, gọi callback để chạy tool, trả kết quả.
  * (Test thật với service Python: xem engines/pydanticai-engine/README.md.)
  */
class RemoteEngineSpec extends CatsEffectSuite:

  override def munitIOTimeout = 30.seconds

  /** Engine giả, đóng vai plumber như demo_model.py. */
  private def fakeEngine(port: Int): Resource[IO, Unit] =
    EmberClientBuilder.default[IO].build.flatMap { client =>
      val routes = HttpRoutes.of[IO] { case req @ POST -> Root / "v1" / "turns" =>
        req.as[Json].flatMap { body =>
          val c        = body.hcursor
          val callback = c.downField("callback").downField("url").as[String].toOption.get
          val lastUser = c.downField("messages").values.get.toList.reverse
                           .find(_.hcursor.downField("role").as[String].contains("user"))
                           .flatMap(_.hcursor.downField("content").as[String].toOption).getOrElse("")
          val (tool, args, reply) =
            if lastUser.startsWith("Pending messages: ") then
              ("contact_human", Json.obj("message" -> s"[remote] ${lastUser.split(": ", 3).last}".asJson), "Đã hỏi thợ.")
            else ("chat_with_agent", Json.obj("agent_name" -> "dispatcher".asJson, "question" -> s"Plumber says: ${lastUser.split("User: ").last}".asJson), "Đã chuyển lời.")
          val invoke = Request[IO](Method.POST, Uri.unsafeFromString(s"$callback/$tool"))
                         .withEntity(Json.obj("tool_call_id" -> "r1".asJson, "arguments" -> args))
          client.expect[Json](invoke).flatMap { res =>
            val result = res.hcursor.downField("result").as[String].getOrElse("")
            Ok(Json.obj(
              "reply" -> reply.asJson,
              "tool_calls" -> Json.arr(Json.obj("id" -> "r1".asJson, "name" -> tool.asJson, "arguments" -> args.noSpaces.asJson, "result" -> result.asJson)),
              "stopped_early" -> false.asJson
            ))
          }
        }
      }
      EmberServerBuilder.default[IO].withHost(Host.fromString("127.0.0.1").get).withPort(Port.fromInt(port).get)
        .withShutdownTimeout(Duration.Zero).withHttpApp(routes.orNotFound).build.map(_ => ())
    }

  private def system(mahiloPort: Int, enginePort: Int) =
    for
      _         <- fakeEngine(enginePort)
      store     <- Resource.eval(InMemoryMessageStore.make)
      sessions  <- Resource.eval(InMemorySessionRepository.make)
      telemetry <- Resource.eval(InMemoryTelemetry.make)
      human     <- Resource.eval(LocalHumanGateway.make(Set(TestKit.dispatcher, TestKit.plumber), echo = false))
      registry  <- Resource.eval(RemoteTurnRegistry.make)
      gateway   <- Resource.eval(WebSocketHumanGateway.make)
      remote     = RemoteAgentEngine(s"http://127.0.0.1:$enginePort", s"http://127.0.0.1:$mahiloPort", registry)
      agents     = List(AgentDefinition(CentralizedTemplates.dispatcher), AgentDefinition(CentralizedTemplates.plumber, engine = Some(remote)))
      mahilo    <- Mahilo.resource(agents, List(TestKit.dispatcher), MahiloPorts(DemoBrain.llm, store, sessions, human, telemetry), MahiloConfig())
      _         <- HttpServer.resource(mahilo, gateway, "127.0.0.1", mahiloPort, Duration.Zero, RemoteEngineRoutes(registry).routes)
    yield (mahilo, store, human, sessions)

  test("plumber chạy ở engine ngoài: tool đi qua callback, kịch bản chạy trọn") {
    val base = 20000 + scala.util.Random.nextInt(2000)
    system(base, base + 1).use { (mahilo, store, human, sessions) =>
      for
        _  <- mahilo.chat.chat(TestKit.dispatcher, "Toilet nhà tôi bị tắc")
        d1 <- TestKit.eventually(human.delivered)(_.size == 1)
        _  <- mahilo.chat.chat(TestKit.plumber, "Chiều nay 3h tôi đến được")
        d2 <- TestKit.eventually(human.delivered)(_.size == 2)
        ms <- TestKit.eventually(store.query(MessageQuery()))(m => m.size == 2 && m.forall(_.state == DeliveryState.Processed))
        s  <- sessions.load(TestKit.plumber)
      yield
        assert(d1.head._2.startsWith("[remote]"), d1)
        assert(d2(1)._2.contains("3h"), d2)
        assert(s.messages.exists { case ChatMessage.Tool(_, "contact_human", _) => true; case _ => false }, s.messages)
    }
  }

  test("callback cho lượt không tồn tại trả 404") {
    val base = 22000 + scala.util.Random.nextInt(2000)
    system(base, base + 1).use { _ =>
      IO.blocking {
        HttpClient.newHttpClient().send(
          HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$base/engine/turns/nope/tools/contact_human"))
            .POST(HttpRequest.BodyPublishers.ofString("""{"arguments":{"message":"x"}}""")).build(),
          HttpResponse.BodyHandlers.ofString()
        ).statusCode()
      }.map(code => assertEquals(code, 404))
    }
  }
