package mahilo.adapters.llm.anthropic

import cats.effect.{IO, Resource}
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import mahilo.application.port.out.{LlmClient, LlmReply, LlmRequest}

/** ADAPTER cho port `LlmClient`: gọi Claude qua SDK Java chính thức của Anthropic
  * (`com.anthropic:anthropic-java`).
  *
  * Lõi Mahilo không đổi gì: agent dùng engine gốc gọi thẳng adapter này; agent LangGraph4j
  * dùng nó qua `ChatModelFromLlmClient` (xem `ServerMain`).
  */
final class AnthropicLlmClient(client: AnthropicClient, settings: AnthropicSettings) extends LlmClient:

  private val thinking = ThinkingCache()

  def complete(request: LlmRequest): IO[LlmReply] =
    IO(AnthropicConversions.params(request, settings, thinking.lookup))
      .flatMap(p => IO.blocking(client.messages().create(p)))
      .map { message =>
        thinking.remember(message)
        AnthropicConversions.reply(message)
      }

object AnthropicLlmClient:

  /** @param apiKey  None = SDK tự đọc `ANTHROPIC_API_KEY` (hoặc profile của `ant auth login`)
    * @param baseUrl None = https://api.anthropic.com
    */
  def resource(settings: AnthropicSettings, apiKey: Option[String] = None, baseUrl: Option[String] = None): Resource[IO, AnthropicLlmClient] =
    Resource
      .make(IO {
        val b = AnthropicOkHttpClient.builder().fromEnv()
        apiKey.foreach(b.apiKey)
        baseUrl.foreach(b.baseUrl)
        b.build()
      })(c => IO.blocking(c.close()))
      .map(AnthropicLlmClient(_, settings))
