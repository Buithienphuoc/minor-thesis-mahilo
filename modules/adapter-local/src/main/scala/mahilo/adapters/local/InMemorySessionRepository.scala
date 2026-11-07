package mahilo.adapters.local

import cats.effect.{IO, Ref}
import mahilo.application.port.out.SessionRepository
import mahilo.domain.AgentName
import mahilo.domain.conversation.Session

/** ADAPTER cho port `SessionRepository`, lưu trong RAM. */
final class InMemorySessionRepository private (ref: Ref[IO, Map[AgentName, Session]]) extends SessionRepository:
  def load(agent: AgentName): IO[Session] = ref.get.map(_.getOrElse(agent, Session.empty(agent)))
  def save(session: Session): IO[Unit]    = ref.update(_.updated(session.agent, session))

object InMemorySessionRepository:
  def make: IO[InMemorySessionRepository] = Ref.of[IO, Map[AgentName, Session]](Map.empty).map(InMemorySessionRepository(_))
