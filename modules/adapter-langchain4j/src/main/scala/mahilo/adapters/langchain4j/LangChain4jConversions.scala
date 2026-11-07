package mahilo.adapters.langchain4j

import scala.jdk.CollectionConverters.*
import dev.langchain4j.agent.tool.{ToolExecutionRequest, ToolSpecification}
import dev.langchain4j.data.message as lc
import dev.langchain4j.model.chat.request.json.JsonObjectSchema
import mahilo.domain.conversation.{ChatMessage, ToolCall}
import mahilo.domain.tool.{ParamType, ToolSpec}

/** Chuyển đổi hai chiều giữa kiểu domain của Mahilo và kiểu của LangChain4j.
  * Đây là "lớp chống ăn mòn" (anti-corruption layer): kiểu LangChain4j không bao giờ lọt
  * vào domain hay application.
  */
object LangChain4jConversions:

  def toLc(m: ChatMessage): lc.ChatMessage = m match
    case ChatMessage.System(c) => lc.SystemMessage.from(c)
    case ChatMessage.User(c)   => lc.UserMessage.from(c)
    case ChatMessage.Assistant(c, Nil) => lc.AiMessage.from(c.getOrElse(""))
    case ChatMessage.Assistant(c, calls) =>
      val reqs = calls.map(call => ToolExecutionRequest.builder().id(call.id).name(call.name).arguments(call.rawArguments).build())
      c.fold(lc.AiMessage.from(reqs.asJava))(text => lc.AiMessage.from(text, reqs.asJava))
    case ChatMessage.Tool(id, name, c) => lc.ToolExecutionResultMessage.from(id, name, c)

  def fromLc(m: lc.ChatMessage): ChatMessage = m match
    case s: lc.SystemMessage => ChatMessage.System(s.text())
    case u: lc.UserMessage   => ChatMessage.User(u.singleText())
    case a: lc.AiMessage     => ChatMessage.Assistant(Option(a.text()).filter(_.nonEmpty), toolCalls(a))
    case t: lc.ToolExecutionResultMessage => ChatMessage.Tool(t.id(), t.toolName(), t.text())
    case other               => ChatMessage.User(other.toString)

  def toolCalls(a: lc.AiMessage): List[ToolCall] =
    if !a.hasToolExecutionRequests() then Nil
    else a.toolExecutionRequests().asScala.toList.map(r => toolCall(r))

  def toolCall(r: ToolExecutionRequest): ToolCall =
    val raw = Option(r.arguments()).getOrElse("{}")
    ToolCall(r.id(), r.name(), JsonArgs.parse(raw), raw)

  def toolSpec(t: ToolSpec): ToolSpecification =
    val schema = t.params.foldLeft(JsonObjectSchema.builder()) { (b, p) =>
      p.tpe match
        case ParamType.StringType  => b.addStringProperty(p.name, p.description)
        case ParamType.NumberType  => b.addNumberProperty(p.name, p.description)
        case ParamType.BooleanType => b.addBooleanProperty(p.name, p.description)
    }.required(t.params.filter(_.required).map(_.name).asJava).build()
    ToolSpecification.builder().name(t.name).description(t.description).parameters(schema).build()

/** Đọc JSON tham số tool thành Map bằng Jackson (LangChain4j đã kéo theo Jackson). */
object JsonArgs:
  private val mapper = com.fasterxml.jackson.databind.ObjectMapper()

  def parse(raw: String): Map[String, String] =
    try
      val node = mapper.readTree(raw)
      if node == null || !node.isObject then Map.empty
      else node.properties().asScala.map(e => e.getKey -> (if e.getValue.isTextual then e.getValue.asText() else e.getValue.toString)).toMap
    catch case _: Exception => Map.empty
