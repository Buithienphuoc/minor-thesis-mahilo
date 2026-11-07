package mahilo.application.service

import cats.effect.IO
import mahilo.domain.agent.AgentSpec
import mahilo.domain.tool.ToolSpec
import mahilo.application.port.out.AgentEngine

/** Tool do người dùng framework tự viết. Python: `tools=[{"tool": {...}, "function": f}]`.
  *
  * Mô tả (`spec`) là domain; cách chạy (`run`) có hiệu ứng nên nằm ở application.
  */
final case class CustomTool(spec: ToolSpec, run: Map[String, String] => IO[String])

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
