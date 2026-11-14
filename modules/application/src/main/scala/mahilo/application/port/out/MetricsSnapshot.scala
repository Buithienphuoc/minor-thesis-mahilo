package mahilo.application.port.out


/** Ảnh chụp số liệu: tên sự kiện → số lần, và vài số đo thời gian. */
final case class MetricsSnapshot(counters: Map[String, Long], llmLatencyAvgMillis: Option[Double])
