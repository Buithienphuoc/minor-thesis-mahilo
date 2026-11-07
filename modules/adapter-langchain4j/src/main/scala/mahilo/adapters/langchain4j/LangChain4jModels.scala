package mahilo.adapters.langchain4j

import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.openai.OpenAiChatModel

/** Nhà máy tạo `ChatModel` của LangChain4j. Thêm provider khác (Anthropic, Ollama...)
  * bằng cách thêm dependency `langchain4j-<provider>` và một hàm ở đây.
  */
object LangChain4jModels:
  def openAi(apiKey: String, model: String = "gpt-4o-mini", baseUrl: Option[String] = None): ChatModel =
    val b = OpenAiChatModel.builder().apiKey(apiKey).modelName(model).temperature(0.7)
    baseUrl.fold(b)(b.baseUrl).build()
