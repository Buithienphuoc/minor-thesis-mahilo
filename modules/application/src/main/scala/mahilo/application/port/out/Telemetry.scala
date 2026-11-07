package mahilo.application.port.out

import cats.effect.IO
import mahilo.domain.AgentName
import mahilo.domain.telemetry.TelemetryEvent

/** Ảnh chụp số liệu: tên sự kiện → số lần, và vài số đo thời gian. */
final case class MetricsSnapshot(counters: Map[String, Long], llmLatencyAvgMillis: Option[Double])

/** PORT RA: ghi nhận sự kiện để quan sát hệ thống. Python: `MahiloTelemetry` (OpenTelemetry). */
trait Telemetry:
  def record(event: TelemetryEvent): IO[Unit]
  def snapshot(agent: Option[AgentName]): IO[MetricsSnapshot]
