package mahilo.adapters.llm.openai

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import cats.effect.IO
import mahilo.application.port.out.{LlmClient, LlmReply, LlmRequest}

/** ADAPTER cho port `LlmClient`: gọi HTTP tới bất kỳ API nào tương thích OpenAI Chat
  * Completions (OpenAI, Azure OpenAI, Ollama, vLLM, LM Studio, OpenRouter...).
  *
  * Python dùng LiteLLM để nói chuyện với nhiều provider. Trên JVM không có thư viện y
  * hệt; phần lớn provider đều có endpoint tương thích OpenAI nên một adapter là đủ.
  * Nếu cần provider không tương thích, dùng `LangChain4jLlmClient` (adapter-langchain4j).
  *
  * Dùng `java.net.http.HttpClient` có sẵn trong JDK để code ngắn và dễ đọc.
  */
final class OpenAiCompatibleLlmClient(
    apiKey: String,
    model: String = "gpt-4o-mini",
    baseUrl: String = "https://api.openai.com/v1",
    timeout: Duration = Duration.ofSeconds(60)
) extends LlmClient:

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  def complete(request: LlmRequest): IO[LlmReply] =
    val body = OpenAiJson.request(request, model).noSpaces
    val httpRequest = HttpRequest
      .newBuilder(URI.create(s"${baseUrl.stripSuffix("/")}/chat/completions"))
      .timeout(timeout)
      .header("Content-Type", "application/json")
      .header("Authorization", s"Bearer $apiKey")
      .POST(HttpRequest.BodyPublishers.ofString(body))
      .build()

    IO.fromCompletableFuture(IO(http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString()))).flatMap { res =>
      if res.statusCode() / 100 != 2 then IO.raiseError(RuntimeException(s"LLM HTTP ${res.statusCode()}: ${res.body().take(500)}"))
      else IO.fromEither(OpenAiJson.reply(res.body()).left.map(RuntimeException(_)))
    }
