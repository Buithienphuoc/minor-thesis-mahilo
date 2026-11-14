package mahilo.adapters.llm.anthropic

import scala.jdk.CollectionConverters.*
import com.anthropic.core.{JsonValue, ObjectMappers}
import com.anthropic.models.messages.*
import mahilo.application.port.out.{LlmReply, LlmRequest}
import mahilo.domain.conversation.{ChatMessage, ToolCall}
import mahilo.domain.tool.ToolSpec

/** Chuyển đổi giữa kiểu domain của Mahilo và kiểu của SDK Java chính thức của Anthropic.
  * Tách khỏi phần gọi mạng để đọc và test dễ.
  *
  * Khác biệt chính so với định dạng OpenAI (xem `OpenAiJson`):
  *   - `system` là tham số riêng, không phải một tin trong `messages`.
  *   - Không có vai `tool`: kết quả tool là block `tool_result` nằm trong tin `user`. Nhiều kết quả
  *     liền nhau (và tin user theo sau) gộp thành MỘT tin user.
  *   - Câu trả lời của Claude có thể chứa block `thinking`. Trong cùng một lượt (vòng lặp tool),
  *     phải gửi lại nguyên văn các block đó cùng với `tool_use`, nếu không Claude mất mạch suy luận.
  *     Domain không biết gì về `thinking`, nên adapter giữ chúng trong [[ThinkingCache]], tra theo
  *     id của lời gọi tool.
  */
object AnthropicConversions:

  private val mapper = ObjectMappers.jsonMapper()

  /** Cấu hình gửi kèm mỗi request (đọc từ biến môi trường ở `ServerMain`). */
  final case class Settings(
      model: String = "claude-opus-5-5",
      maxTokens: Long = 16000L,
      effort: Option[String] = None, // low | medium | high | xhigh | max; None = mặc định của model
      fallbacks: Boolean = true      // khi Claude từ chối vì bộ lọc an toàn, server tự thử model dự phòng
  )

  /** Beta header: `fallbacks` (dự phòng khi bị từ chối) và `block_binding` (xem `params`). */
  val Betas: List[String] = List("server-side-fallback-2026-07-01", "thinking-binding-controls-2026-08-01")

  def params(req: LlmRequest, s: Settings, cached: String => Option[MessageParam]): MessageCreateParams =
    val b = MessageCreateParams.builder()
      .model(req.model.getOrElse(s.model))
      .maxTokens(s.maxTokens)
      // Adaptive thinking: Claude tự quyết nghĩ bao nhiêu. Nếu lịch sử bị sửa (vd Mahilo cắt bớt tin cũ)
      // thì API bỏ các block thinking không còn hợp lệ thay vì trả lỗi 400.
      .thinking(
        ThinkingConfigAdaptive.builder()
          .putAdditionalProperty("block_binding", JsonValue.from(java.util.Map.of("prefix_mismatch_behavior", "drop_block")))
          .build()
      )
      .messages(messages(req.messages, cached).asJava)
      .putAdditionalHeader("anthropic-beta", Betas.mkString(","))
    // Không gửi `temperature`: các model Claude hiện tại không nhận tham số này.
    val system = req.messages.collect { case ChatMessage.System(c) => c }
    if system.nonEmpty then b.system(system.mkString("\n\n"))
    req.tools.foreach(t => b.addTool(tool(t)))
    s.effort.foreach(e => b.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(e)).build()))
    if s.fallbacks then b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
    b.build()

  /** Danh sách tin domain → `messages` của Anthropic (không gồm tin System). */
  def messages(all: List[ChatMessage], cached: String => Option[MessageParam]): List[MessageParam] =
    val chat     = all.filterNot(_.isInstanceOf[ChatMessage.System])
    val lastUser = chat.lastIndexWhere(_.isInstanceOf[ChatMessage.User])
    val out      = List.newBuilder[MessageParam]
    var pending  = Vector.empty[ContentBlockParam] // các block của tin user đang gom
    def flush(): Unit =
      if pending.nonEmpty then out += MessageParam.ofUserOfContentBlockParams(pending.asJava)
      pending = Vector.empty

    chat.zipWithIndex.foreach {
      case (ChatMessage.User(c), _) => pending :+= text(c)
      case (ChatMessage.Tool(id, _, c), _) =>
        pending :+= ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(id).content(c).build())
      case (ChatMessage.Assistant(c, calls), i) =>
        // Chỉ phát lại block thinking của lượt hiện tại (sau tin user cuối). Bỏ thinking của các lượt
        // trước là hợp lệ (bỏ cả một đoạn ở đầu), và tránh lỗi khi prompt hệ thống đã đổi giữa các lượt.
        val replay = if i > lastUser then calls.headOption.flatMap(call => cached(call.id)) else None
        val blocks = c.filter(_.trim.nonEmpty).map(text).toList ++ calls.map(toolUse)
        if replay.isDefined || blocks.nonEmpty then
          flush()
          out += replay.getOrElse(MessageParam.ofAssistantOfContentBlockParams(blocks.asJava))
      case (_: ChatMessage.System, _) => ()
    }
    flush()
    val result = out.result()
    // API yêu cầu tin đầu tiên là của user.
    if result.headOption.exists(_.isAssistant) then MessageParam.ofUser("(tiếp tục cuộc trò chuyện)") :: result else result

  def tool(t: ToolSpec): Tool =
    val props = Tool.InputSchema.Properties.builder()
    t.params.foreach { p =>
      props.putAdditionalProperty(p.name, JsonValue.from(java.util.Map.of("type", p.tpe.jsonSchemaName, "description", p.description)))
    }
    Tool.builder()
      .name(t.name)
      .description(t.description)
      .inputSchema(Tool.InputSchema.builder().properties(props.build()).required(t.params.filter(_.required).map(_.name).asJava).build())
      .build()

  /** Câu trả lời của Claude → `LlmReply` của domain. */
  def reply(m: Message): LlmReply =
    if m.stopReason().toScala.contains(StopReason.REFUSAL) then
      val why = m.stopDetails().toScala.flatMap(_.explanation().toScala).getOrElse("")
      LlmReply(Some(s"Xin lỗi, Claude đã từ chối yêu cầu này. $why".trim))
    else
      val blocks = m.content().asScala.toList
      val texts  = blocks.flatMap(_.text().toScala.map(_.text())).filter(_.nonEmpty)
      val calls  = blocks.flatMap(_.toolUse().toScala).map { u =>
        val raw = mapper.writeValueAsString(u._input())
        ToolCall(u.id(), u.name(), arguments(raw), raw)
      }
      LlmReply(Option.when(texts.nonEmpty)(texts.mkString("\n")), calls)

  /** JSON tham số → Map; giá trị không phải chuỗi giữ dạng JSON (giống `OpenAiJson.arguments`). */
  def arguments(raw: String): Map[String, String] =
    val node = mapper.readTree(raw)
    if node == null || !node.isObject then Map.empty
    else node.properties().asScala.map(e => e.getKey -> (if e.getValue.isTextual then e.getValue.asText() else e.getValue.toString)).toMap

  private def text(c: String): ContentBlockParam = ContentBlockParam.ofText(TextBlockParam.builder().text(c).build())

  private def toolUse(call: ToolCall): ContentBlockParam =
    val input = ToolUseBlockParam.Input.builder()
    val node  = mapper.readTree(if call.rawArguments.trim.isEmpty then "{}" else call.rawArguments)
    if node != null && node.isObject then node.properties().asScala.foreach(e => input.putAdditionalProperty(e.getKey, JsonValue.fromJsonNode(e.getValue)))
    ContentBlockParam.ofToolUse(ToolUseBlockParam.builder().id(call.id).name(call.name).input(input.build()).build())

  extension [A](o: java.util.Optional[A]) private def toScala: Option[A] = if o.isPresent then Some(o.get) else None
