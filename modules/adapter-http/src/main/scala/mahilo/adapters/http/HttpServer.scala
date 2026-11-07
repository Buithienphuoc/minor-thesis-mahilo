package mahilo.adapters.http

import scala.concurrent.duration.*
import cats.effect.{IO, Resource}
import cats.syntax.all.*
import org.http4s.HttpRoutes
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server
import mahilo.application.Mahilo

/** Dựng server Ember (http4s). Python: `ServerManager.run()` với uvicorn. */
object HttpServer:
  /** @param shutdownTimeout khi tắt, chờ tối đa bấy lâu cho các kết nối (kể cả WebSocket) đóng
    * @param extraRoutes     route của adapter khác cần gắn vào cùng server (vd callback engine ngoài)
    */
  def resource(
      mahilo: Mahilo,
      gateway: WebSocketHumanGateway,
      host: String,
      port: Int,
      shutdownTimeout: FiniteDuration = 5.seconds,
      extraRoutes: HttpRoutes[IO] = HttpRoutes.empty[IO]
  ): Resource[IO, Server] =
    EmberServerBuilder
      .default[IO]
      .withHost(Host.fromString(host).getOrElse(Host.fromString("0.0.0.0").get))
      .withPort(Port.fromInt(port).getOrElse(Port.fromInt(8000).get))
      .withShutdownTimeout(shutdownTimeout)
      .withHttpWebSocketApp(ws => (extraRoutes <+> MahiloRoutes(mahilo, gateway).routes(ws)).orNotFound)
      .build
