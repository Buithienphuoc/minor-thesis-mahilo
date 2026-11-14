package mahilo.application.port.in

import mahilo.domain.AgentName

final case class AgentStatus(name: AgentName, kind: String, shortDescription: String, active: Boolean)
