package mahilo.adapters.local

import cats.effect.{IO, Ref}
import cats.effect.std.Console
import mahilo.application.port.out.HumanGateway
import mahilo.domain.AgentName

/** ADAPTER cho port `HumanGateway`: in ra console và ghi lại (để test kiểm tra).
  *
  * @param connected agent nào được coi là "có người đang nghe"
  */
final class LocalHumanGateway private (
    connected: Set[AgentName],
    echo: Boolean,
    log: Ref[IO, Vector[(AgentName, String)]]
) extends HumanGateway:

  def send(agent: AgentName, text: String): IO[Int] =
    if !connected.contains(agent) then IO.pure(0)
    else
      log.update(_ :+ (agent -> text)) *>
        IO.whenA(echo)(Console[IO].println(s"  📣 [${agent.value} → người dùng của ${agent.value}] $text")).as(1)

  def delivered: IO[Vector[(AgentName, String)]] = log.get

object LocalHumanGateway:
  def make(connected: Set[AgentName], echo: Boolean = true): IO[LocalHumanGateway] =
    Ref.of[IO, Vector[(AgentName, String)]](Vector.empty).map(LocalHumanGateway(connected, echo, _))
