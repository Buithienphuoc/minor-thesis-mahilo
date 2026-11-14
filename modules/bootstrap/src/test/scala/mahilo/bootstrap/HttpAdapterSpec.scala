package mahilo.bootstrap

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse, WebSocket}
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}
import scala.concurrent.duration.*
import cats.effect.{IO, Resource}
import munit.CatsEffectSuite
import mahilo.adapters.http.{HttpServer, WebSocketHumanGateway}
import mahilo.adapters.local.*
import mahilo.application.{Mahilo, MahiloConfig, MahiloPorts}
import mahilo.application.service.AgentDefinition
import mahilo.bootstrap.templates.CentralizedTemplates

/** Chạy server thật trên một cổng ngẫu nhiên và gọi bằng HTTP/WebSocket của JDK. */
class HttpAdapterSpec extends CatsEffectSuite:

  override def munitIOTimeout = 30.seconds

  private val server: Resource[IO, Int] =
    for
      store     <- Resource.eval(InMemoryMessageStore.make)
      sessions  <- Resource.eval(InMemorySessionRepository.make)
      telemetry <- Resource.eval(InMemoryTelemetry.make)
      gateway   <- Resource.eval(WebSocketHumanGateway.make)
      ports      = MahiloPorts(DemoBrain.llm, store, sessions, gateway, telemetry)
      mahilo    <- Mahilo.resource(CentralizedTemplates.all.map(AgentDefinition(_)), List(TestKit.dispatcher), ports, MahiloConfig())
      port       = 18000 + scala.util.Random.nextInt(2000)
      _         <- HttpServer.resource(mahilo, gateway, "127.0.0.1", port, shutdownTimeout = Duration.Zero)
    yield port

  private val http = HttpClient.newHttpClient()
  private def get(port: Int, path: String) =
    IO.blocking(http.send(HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port$path")).GET().build(), HttpResponse.BodyHandlers.ofString()))
  private def post(port: Int, path: String, body: String) =
    IO.blocking(http.send(HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port$path")).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()))

  /** Client WebSocket tối giản: gom các frame text nhận được vào một hàng đợi. */
  private def wsConnect(port: Int, agent: String): IO[(WebSocket, LinkedBlockingQueue[String])] = IO.blocking {
    val inbox = LinkedBlockingQueue[String]()
    val listener = QueueingListener(inbox)
    val ws = http.newWebSocketBuilder().buildAsync(URI.create(s"ws://127.0.0.1:$port/ws/$agent"), listener).get(5, TimeUnit.SECONDS)
    (ws, inbox)
  }
  private def next(q: LinkedBlockingQueue[String]): IO[String] =
    IO.blocking(Option(q.poll(5, TimeUnit.SECONDS)).getOrElse(fail("không nhận được frame WebSocket")))

  test("REST: /health, /agents, /chat, /messages") {
    server.use { port =>
      for
        h  <- get(port, "/health")
        ag <- get(port, "/agents")
        c  <- post(port, "/chat/dispatcher", "Toilet nhà tôi bị tắc")
        in <- post(port, "/chat/mold_specialist", "hello") // chưa ai bật agent này
        nf <- post(port, "/chat/ghost", "hello")
        ms <- TestKit.eventually(get(port, "/messages"))(_.body().contains("processed"))
      yield
        assertEquals(h.statusCode(), 200)
        assert(ag.body().contains("\"name\":\"plumber\""), ag.body())
        assertEquals(c.statusCode(), 200)
        assert(c.body().contains("liên hệ thợ"), c.body())
        assertEquals(in.statusCode(), 409)
        assertEquals(nf.statusCode(), 404)
        assert(ms.body().contains("\"sender\":\"dispatcher\""), ms.body())
    }
  }

  test("WebSocket: người dùng của plumber nhận tin contact_human, trả lời thì về tới dispatcher") {
    server.use { port =>
      for
        p  <- wsConnect(port, "plumber")
        d  <- wsConnect(port, "dispatcher")
        (wsP, plumberInbox)    = p
        (wsD, dispatcherInbox) = d
        _  <- IO.fromCompletableFuture(IO(wsD.sendText("Toilet nhà tôi bị tắc", true)))
        r1 <- next(dispatcherInbox)                // câu trả lời trực tiếp của dispatcher
        p1 <- next(plumberInbox)                   // contact_human của plumber
        _  <- IO.fromCompletableFuture(IO(wsP.sendText("Chiều nay 3h tôi đến được", true)))
        p2 <- next(plumberInbox)                   // câu trả lời trực tiếp của plumber
        d2 <- next(dispatcherInbox)                // contact_human của dispatcher
      yield
        assert(r1.contains("liên hệ thợ"), r1)
        assert(p1.contains("Toilet nhà tôi bị tắc"), p1)
        assert(p2.contains("điều phối"), p2)
        assert(d2.contains("3h"), d2)
    }
  }
