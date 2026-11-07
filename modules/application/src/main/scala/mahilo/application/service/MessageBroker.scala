package mahilo.application.service

import cats.effect.IO
import cats.effect.std.UUIDGen
import cats.syntax.all.*
import mahilo.application.port.out.*
import mahilo.domain.{AgentName, MessageId}
import mahilo.domain.messaging.*
import mahilo.domain.policy.{PolicyContext, PolicyViolation}
import mahilo.domain.telemetry.TelemetryEvent

/** Kết quả gửi một tin. Python luôn trả "Message sent" kể cả khi policy chặn. */
enum SendOutcome:
  case Queued(envelope: Envelope)
  case Rejected(envelope: Envelope, violations: List[PolicyViolation])

/** Bưu điện giữa các agent. Python: `MessageBroker` + `AgentManager.send_message_to_agent`.
  *
  * Khác Python: hàng đợi là event-driven. Lưu tin xong thì gọi `wakeUp(recipient)` để
  * worker xử lý ngay, thay vì vòng lặp poll SQLite mỗi giây.
  */
final class MessageBroker(
    store: MessageStore,
    policies: PolicyEngine,
    signer: EnvelopeSigner,
    telemetry: Telemetry,
    retry: RetryPolicy,
    policyHistorySize: Int,
    wakeUp: AgentName => IO[Unit]
):

  def send(
      sender: AgentName,
      recipient: AgentName,
      payload: String,
      kind: MessageKind = MessageKind.Direct,
      correlationId: Option[String] = None,
      replyTo: Option[MessageId] = None
  ): IO[SendOutcome] =
    for
      id  <- UUIDGen[IO].randomUUID.map(u => MessageId(u.toString))
      now <- IO.realTimeInstant
      env = signer.sign(Envelope(id, sender, recipient, kind, payload, now, correlationId, replyTo))
      // Tin ERROR không bị kiểm duyệt (tránh vòng lặp lỗi ↔ lỗi), giống Python.
      violations <-
        if kind == MessageKind.Error then IO.pure(Nil)
        else store.conversation(sender, recipient, policyHistorySize).flatMap(h => policies.evaluate(env, PolicyContext(h, now)))
      outcome <-
        if violations.nonEmpty then
          telemetry.record(TelemetryEvent.MessageRejected(env, violations)).as(SendOutcome.Rejected(env, violations))
        else
          store.save(env) *>
            telemetry.record(TelemetryEvent.MessageSent(env)) *>
            wakeUp(recipient).as(SendOutcome.Queued(env))
    yield outcome

  def pending(agent: AgentName): IO[List[StoredMessage]] = store.pending(agent)

  def acknowledge(env: Envelope): IO[Unit] =
    store.updateState(env.id, DeliveryState.Processed) *> telemetry.record(TelemetryEvent.MessageProcessed(env))

  /** Ghi nhận xử lý thất bại. Hết lượt thử thì báo lỗi ngược về người gửi.
    * @return trạng thái mới (Pending = sẽ thử lại, Failed = bỏ cuộc)
    */
  def fail(msg: StoredMessage, cause: Throwable): IO[DeliveryState] =
    val next = retry.afterFailure(msg.state)
    val env  = msg.envelope
    store.updateState(env.id, next) *> (next match
      case DeliveryState.Pending(n) => telemetry.record(TelemetryEvent.MessageRetried(env, n))
      case DeliveryState.Failed(n) =>
        telemetry.record(TelemetryEvent.MessageFailed(env, n)) *>
          send(
            sender = env.recipient,
            recipient = env.sender,
            payload = s"Failed to process message after max retries: ${cause.getMessage}",
            kind = MessageKind.Error,
            correlationId = env.correlationId,
            replyTo = Some(env.id)
          ).void
      case DeliveryState.Processed => IO.unit
    ).as(next)

  /** Tin sai chữ ký: đánh dấu Failed luôn (Python bỏ qua nhưng để nguyên pending,
    * nên tin đó bị quét lại vô hạn).
    */
  def rejectForgery(msg: StoredMessage): IO[Unit] =
    store.updateState(msg.envelope.id, DeliveryState.Failed(msg.state.retryCount)) *>
      telemetry.record(TelemetryEvent.MessageFailed(msg.envelope, msg.state.retryCount))

  def verify(env: Envelope): Boolean = signer.verify(env)
