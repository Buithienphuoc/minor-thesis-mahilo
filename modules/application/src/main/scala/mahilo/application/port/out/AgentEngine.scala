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

/** @param reply        câu trả lời văn bản cuối cùng
  * @param newMessages  các tin assistant/tool sinh ra trong lượt (để lưu vào session)
  * @param stoppedEarly true nếu bị cắt vì vượt `maxIterations`
  */
final case class TurnResult(reply: Option[String], newMessages: Vector[ChatMessage], stoppedEarly: Boolean)

/** PORT RA: "bộ máy" chạy một lượt của agent.
  *
  * Python có hai kiểu agent: `BaseAgent` (tự viết vòng lặp tool-calling với LiteLLM)
  * và `LangGraphAgent`/`PydanticAIAgent` (giao cho framework khác). Ở đây đó là các
  * hiện thực khác nhau của cùng một port:
  *  - `ToolLoopEngine` (tầng application): vòng lặp gốc của Mahilo, dùng `LlmClient`;
  *  - `LangGraph4jEngine` (adapter-langchain4j): chạy một `StateGraph` của LangGraph4j.
  */
trait AgentEngine:
  def run(turn: AgentTurn): IO[TurnResult]
