package mahilo.application.port.out

import cats.effect.IO
import mahilo.domain.conversation.{ChatMessage, ToolCall}
import mahilo.domain.tool.ToolSpec

/** Yêu cầu gửi cho LLM. Không gắn với nhà cung cấp nào. */
final case class LlmRequest(
    messages: List[ChatMessage],
    tools: List[ToolSpec] = Nil,
    temperature: Double = 0.7,
    model: Option[String] = None // None = dùng model mặc định của adapter
)

/** Câu trả lời của LLM: có thể là văn bản, có thể là danh sách lời gọi tool, hoặc cả hai. */
final case class LlmReply(content: Option[String], toolCalls: List[ToolCall] = Nil):
  def asMessage: ChatMessage = ChatMessage.Assistant(content, toolCalls)

/** PORT RA: "tôi cần một bộ não biết gọi tool".
  *
  * Python gọi thẳng singleton `llm_config` (LiteLLM). Ở đây application chỉ biết trait
  * này; adapter OpenAI, adapter giả lập (cho test/demo)... đều hiện thực nó.
  */
trait LlmClient:
  def complete(request: LlmRequest): IO[LlmReply]
