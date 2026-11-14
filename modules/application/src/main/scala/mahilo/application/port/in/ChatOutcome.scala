package mahilo.application.port.in

import mahilo.domain.AgentName

/** Kết quả một lượt chat trực tiếp. */
enum ChatOutcome:
  case Replied(text: String, activeAgents: List[AgentName])
  case AgentNotFound(agent: AgentName)
  case AgentInactive(agent: AgentName)
