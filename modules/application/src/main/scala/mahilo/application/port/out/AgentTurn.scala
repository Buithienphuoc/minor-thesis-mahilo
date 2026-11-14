package mahilo.application.port.out

import cats.effect.IO
import mahilo.domain.agent.AgentSpec
import mahilo.domain.conversation.{ChatMessage, ToolCall}
import mahilo.domain.tool.ToolSpec

/** Đầu vào của một "lượt suy nghĩ" của agent.
  *
  * @param messages    toàn bộ tin đưa vào mô hình (lịch sử + system prompt + tin mới)
  * @param tools       các tool agent được dùng trong lượt này
  * @param executeTool cách chạy một tool. Engine KHÔNG tự chạy tool: nó gọi lại hàm này,
  *                    nên `chat_with_agent`/`contact_human` luôn đi qua lõi Mahilo
  *                    (broker, policy, telemetry) dù engine là gì.
  */
final case class AgentTurn(
    agent: AgentSpec,
    messages: List[ChatMessage],
    tools: List[ToolSpec],
    executeTool: ToolCall => IO[String],
    maxIterations: Int
)
