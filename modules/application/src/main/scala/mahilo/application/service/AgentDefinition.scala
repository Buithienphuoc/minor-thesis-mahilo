package mahilo.application.service

import mahilo.domain.agent.AgentSpec
import mahilo.application.port.out.AgentEngine

/** Một agent đầy đủ để đăng ký với Mahilo.
  *
  * @param engine bộ máy riêng cho agent này. `None` = dùng engine mặc định (vòng lặp
  *               tool-calling gốc). Python: chọn giữa `BaseAgent` và `LangGraphAgent`.
  */
final case class AgentDefinition(
    spec: AgentSpec,
    tools: List[CustomTool] = Nil,
    engine: Option[AgentEngine] = None
)
