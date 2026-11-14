package mahilo.bootstrap

import mahilo.application.service.AgentDefinition
import mahilo.domain.AgentName

/** Một "ví dụ" của `ServerMain` = bộ agent + agent nào bật sẵn. */
private[bootstrap] final case class Example(agents: List[AgentDefinition], startActive: List[AgentName])
