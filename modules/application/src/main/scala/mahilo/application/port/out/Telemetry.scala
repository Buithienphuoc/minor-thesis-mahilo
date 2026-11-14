package mahilo.application.port.out

import cats.effect.IO
import mahilo.domain.AgentName
import mahilo.domain.telemetry.TelemetryEvent

/** PORT RA: ghi nhận sự kiện để quan sát hệ thống. Python: `MahiloTelemetry` (OpenTelemetry). */
trait Telemetry:
  def record(event: TelemetryEvent): IO[Unit]
  def snapshot(agent: Option[AgentName]): IO[MetricsSnapshot]
