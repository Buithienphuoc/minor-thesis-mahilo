package mahilo.adapters.local

import cats.effect.{IO, Ref}
import mahilo.application.port.out.{MetricsSnapshot, Telemetry}
import mahilo.domain.AgentName
import mahilo.domain.telemetry.TelemetryEvent

/** ADAPTER cho port `Telemetry`: đếm sự kiện trong RAM. Python: phần metric in-memory của
  * `MahiloTelemetry` (bản Python còn đẩy sang OpenTelemetry; muốn vậy thì viết thêm một
  * adapter OTel hiện thực cùng port này).
  */
final class InMemoryTelemetry private (events: Ref[IO, Vector[TelemetryEvent]]) extends Telemetry:

  def record(event: TelemetryEvent): IO[Unit] = events.update(_ :+ event)

  def snapshot(agent: Option[AgentName]): IO[MetricsSnapshot] =
    events.get.map { all =>
      val mine      = all.filter(e => agent.forall(_ == e.subject))
      val counters  = mine.groupBy(_.name).view.mapValues(_.size.toLong).toMap
      val latencies = mine.collect { case TelemetryEvent.LlmCalled(_, ms) => ms.toDouble }
      MetricsSnapshot(counters, Option.when(latencies.nonEmpty)(latencies.sum / latencies.size))
    }

  def all: IO[Vector[TelemetryEvent]] = events.get

object InMemoryTelemetry:
  def make: IO[InMemoryTelemetry] = Ref.of[IO, Vector[TelemetryEvent]](Vector.empty).map(InMemoryTelemetry(_))
