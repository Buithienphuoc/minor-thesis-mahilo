package mahilo.application.port.out

import mahilo.domain.conversation.ChatMessage
import mahilo.domain.tool.ToolSpec

/** Yêu cầu gửi cho LLM. Không gắn với nhà cung cấp nào. */
final case class LlmRequest(
    messages: List[ChatMessage],
    tools: List[ToolSpec] = Nil,
    temperature: Double = 0.7,
    model: Option[String] = None // None = dùng model mặc định của adapter
)
