package mahilo.adapters.langchain4j

import java.util
import scala.jdk.CollectionConverters.*
import dev.langchain4j.data.message.{ChatMessage as LcMessage}
import org.bsc.langgraph4j.state.{AgentState, Channel, Channels}

/** State của graph: một kênh `messages` (nối thêm) và một bộ đếm `steps`.
  * Python (LangGraph): `MessagesState` / `{"messages": [...]}`.
  */
final class MahiloGraphState(data: util.Map[String, Object]) extends AgentState(data):
  def messages: List[LcMessage] =
    this.value[util.List[LcMessage]](MahiloGraphState.Messages).map(_.asScala.toList).orElse(Nil)
  def steps: Int = this.value[Integer](MahiloGraphState.Steps).map(_.intValue).orElse(0)

object MahiloGraphState:
  val Messages = "messages"
  val Steps    = "steps"

  /** `appenderWithDuplicate`: hai tin giống hệt nhau (vd hai kết quả tool trùng chữ) vẫn được giữ. */
  val schema: util.Map[String, Channel[?]] = Map[String, Channel[?]](
    Messages -> Channels.appenderWithDuplicate[LcMessage](() => new util.ArrayList[LcMessage]()),
    Steps    -> Channels.base[Integer](() => Integer.valueOf(0))
  ).asJava
