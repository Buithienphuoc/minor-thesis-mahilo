package mahilo.application.port.out

import java.time.Instant
import mahilo.domain.AgentName

/** Bộ lọc cho API quan sát `/messages`. Python: tham số của `get_messages(...)`. */
final case class MessageQuery(
    sender: Option[AgentName] = None,
    recipient: Option[AgentName] = None,
    from: Option[Instant] = None,
    to: Option[Instant] = None,
    limit: Int = 100
)
