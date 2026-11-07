package mahilo.adapters.local

import cats.effect.{IO, Ref}
import mahilo.application.port.out.{MessageQuery, MessageStore}
import mahilo.domain.{AgentName, MessageId}
import mahilo.domain.messaging.{DeliveryState, Envelope, StoredMessage}

/** ADAPTER cho port `MessageStore`: giữ mọi thứ trong RAM (mất khi tắt chương trình).
  * Dùng cho test, demo, và để thấy port có thể hiện thực đơn giản tới mức nào.
  */
final class InMemoryMessageStore private (ref: Ref[IO, Vector[StoredMessage]]) extends MessageStore:

  def save(envelope: Envelope, state: DeliveryState): IO[Unit] =
    ref.update(_ :+ StoredMessage(envelope, state))

  def get(id: MessageId): IO[Option[StoredMessage]] = ref.get.map(_.find(_.envelope.id == id))

  def updateState(id: MessageId, state: DeliveryState): IO[Unit] =
    ref.update(_.map(m => if m.envelope.id == id then m.copy(state = state) else m))

  def pending(recipient: AgentName): IO[List[StoredMessage]] =
    ref.get.map(_.filter(m => m.envelope.recipient == recipient && isPending(m)).sortBy(_.envelope.timestamp).toList)

  def recipientsWithPending: IO[Set[AgentName]] =
    ref.get.map(_.filter(isPending).map(_.envelope.recipient).toSet)

  def conversation(a: AgentName, b: AgentName, limit: Int): IO[List[Envelope]] =
    ref.get.map { all =>
      all.map(_.envelope)
        .filter(e => (e.sender == a && e.recipient == b) || (e.sender == b && e.recipient == a))
        .sortBy(_.timestamp)
        .takeRight(limit)
        .toList
    }

  def query(q: MessageQuery): IO[List[StoredMessage]] =
    ref.get.map { all =>
      all
        .filter(m => q.sender.forall(_ == m.envelope.sender))
        .filter(m => q.recipient.forall(_ == m.envelope.recipient))
        .filter(m => q.from.forall(t => !m.envelope.timestamp.isBefore(t)))
        .filter(m => q.to.forall(t => !m.envelope.timestamp.isAfter(t)))
        .sortBy(_.envelope.timestamp)
        .reverse
        .take(q.limit)
        .toList
    }

  private def isPending(m: StoredMessage) = m.state match
    case DeliveryState.Pending(_) => true
    case _                        => false

object InMemoryMessageStore:
  def make: IO[InMemoryMessageStore] = Ref.of[IO, Vector[StoredMessage]](Vector.empty).map(InMemoryMessageStore(_))
