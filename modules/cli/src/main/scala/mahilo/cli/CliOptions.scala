package mahilo.cli

/** Tham số dòng lệnh của [[MahiloCli]]. */
final case class CliOptions(agent: String, url: String = "http://localhost:8000", json: Boolean = false)
