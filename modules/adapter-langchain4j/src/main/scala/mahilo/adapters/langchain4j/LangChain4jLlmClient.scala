package mahilo.adapters.langchain4j

import scala.jdk.CollectionConverters.*
import cats.effect.IO
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import mahilo.application.port.out.{LlmClient, LlmReply, LlmRequest}

/** ADAPTER cho port `LlmClient` dùng `ChatModel` của LangChain4j.
  *
  * Vai trò giống LiteLLM bên Python: một interface chung cho nhiều provider (OpenAI,
  * Anthropic, Gemini, Ollama, Azure, Bedrock...). Chỉ cần đổi `ChatModel` truyền vào.
  *
  * Lưu ý: `request.model` bị bỏ qua vì model đã gắn sẵn trong `ChatModel`.
  */
final class LangChain4jLlmClient(model: ChatModel) extends LlmClient:
  import LangChain4jConversions.*

  def complete(request: LlmRequest): IO[LlmReply] =
    IO.blocking {
      val builder = ChatRequest.builder().messages(request.messages.map(toLc).asJava).temperature(request.temperature)
      val req     = if request.tools.isEmpty then builder.build() else builder.toolSpecifications(request.tools.map(toolSpec).asJava).build()
      model.chat(req).aiMessage()
    }.map(ai => LlmReply(Option(ai.text()).filter(_.nonEmpty), toolCalls(ai)))
