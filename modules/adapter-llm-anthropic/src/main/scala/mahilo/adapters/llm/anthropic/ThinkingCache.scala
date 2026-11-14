package mahilo.adapters.llm.anthropic

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*
import com.anthropic.models.messages.{Message, MessageParam}

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
