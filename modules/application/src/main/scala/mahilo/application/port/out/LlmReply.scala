package mahilo.application.port.out

import mahilo.domain.conversation.{ChatMessage, ToolCall}

/** Câu trả lời của LLM: có thể là văn bản, có thể là danh sách lời gọi tool, hoặc cả hai. */
final case class LlmReply(content: Option[String], toolCalls: List[ToolCall] = Nil):
  def asMessage: ChatMessage = ChatMessage.Assistant(content, toolCalls)
