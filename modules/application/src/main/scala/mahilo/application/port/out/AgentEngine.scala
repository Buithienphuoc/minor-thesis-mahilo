package mahilo.application.port.out

import cats.effect.IO

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
