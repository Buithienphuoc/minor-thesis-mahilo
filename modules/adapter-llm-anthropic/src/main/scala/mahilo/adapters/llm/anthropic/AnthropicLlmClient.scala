package mahilo.adapters.llm.anthropic

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*
import cats.effect.{IO, Resource}
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.{Message, MessageParam}
import mahilo.application.port.out.{LlmClient, LlmReply, LlmRequest}
import mahilo.adapters.llm.anthropic.AnthropicConversions.Settings

/** ADAPTER cho port `LlmClient`: gọi Claude qua SDK Java chính thức của Anthropic
  * (`com.anthropic:anthropic-java`).
  *
  * Lõi Mahilo không đổi gì: agent dùng engine gốc gọi thẳng adapter này; agent LangGraph4j
  * dùng nó qua `ChatModelFromLlmClient` (xem `ServerMain`).
  */
final class AnthropicLlmClient(client: AnthropicClient, settings: Settings) extends LlmClient:

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
  def resource(settings: Settings, apiKey: Option[String] = None, baseUrl: Option[String] = None): Resource[IO, AnthropicLlmClient] =
    Resource
      .make(IO {
        val b = AnthropicOkHttpClient.builder().fromEnv()
        apiKey.foreach(b.apiKey)
        baseUrl.foreach(b.baseUrl)
        b.build()
      })(c => IO.blocking(c.close()))
      .map(AnthropicLlmClient(_, settings))

/** Giữ nguyên văn câu trả lời của Claude (kể cả block `thinking`) cho tới khi kết quả tool quay về,
  * tra theo id của lời gọi tool đầu tiên. Có giới hạn kích thước để không phình bộ nhớ.
  */
final class ThinkingCache(maxEntries: Int = 2000):
  private val entries = ConcurrentHashMap[String, MessageParam]()
  private val order   = java.util.concurrent.ConcurrentLinkedQueue[String]()

  def lookup(toolCallId: String): Option[MessageParam] = Option(entries.get(toolCallId))

  def remember(m: Message): Unit =
    m.content().asScala.flatMap(b => if b.isToolUse then Some(b.asToolUse().id()) else None).headOption.foreach { id =>
      entries.put(id, m.toParam())
      order.add(id)
      while order.size > maxEntries do Option(order.poll()).foreach(entries.remove)
    }
