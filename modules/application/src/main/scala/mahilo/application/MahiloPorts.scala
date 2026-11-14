package mahilo.application

import cats.syntax.all.*
import mahilo.application.port.out.*

/** Tất cả port ra mà lõi cần. Bootstrap chọn adapter cụ thể cho từng cái. */
final case class MahiloPorts(
    llm: LlmClient,
    store: MessageStore,
    sessions: SessionRepository,
    human: HumanGateway,
    telemetry: Telemetry,
    signer: EnvelopeSigner = EnvelopeSigner.Disabled
)
