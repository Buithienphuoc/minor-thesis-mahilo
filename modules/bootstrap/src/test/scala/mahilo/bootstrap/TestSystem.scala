package mahilo.bootstrap

import mahilo.adapters.local.*
import mahilo.application.Mahilo

/** Dựng một hệ Mahilo hoàn toàn trong RAM cho test. */
final case class TestSystem(
    mahilo: Mahilo,
    store: InMemoryMessageStore,
    human: LocalHumanGateway,
    telemetry: InMemoryTelemetry,
    sessions: InMemorySessionRepository
)
