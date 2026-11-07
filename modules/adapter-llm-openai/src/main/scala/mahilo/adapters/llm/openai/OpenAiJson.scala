package mahilo.adapters.llm.openai

import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import mahilo.application.port.out.{LlmReply, LlmRequest}
import mahilo.domain.conversation.{ChatMessage, ToolCall}
import mahilo.domain.tool.ToolSpec

/** Chuyển đổi giữa kiểu domain và JSON của OpenAI Chat Completions.
  * Tách riêng khỏi phần HTTP để test được mà không cần mạng.
  */
object OpenAiJson:

  def request(req: LlmRequest, defaultModel: String): Json =
    val base = JsonObject(
      "model"       -> Json.fromString(req.model.getOrElse(defaultModel)),
      "messages"    -> Json.arr(req.messages.map(message)*),
      "temperature" -> Json.fromDoubleOrNull(req.temperature)
    )
    val withTools =
      if req.tools.isEmpty then base
      else base.add("tools", Json.arr(req.tools.map(tool)*)).add("tool_choice", Json.fromString("auto"))
    Json.fromJsonObject(withTools)

  def message(m: ChatMessage): Json = m match
    case ChatMessage.System(c) => Json.obj("role" -> Json.fromString("system"), "content" -> Json.fromString(c))
    case ChatMessage.User(c)   => Json.obj("role" -> Json.fromString("user"), "content" -> Json.fromString(c))
    case ChatMessage.Assistant(c, calls) =>
      val base = JsonObject("role" -> Json.fromString("assistant"), "content" -> c.fold(Json.Null)(Json.fromString))
      Json.fromJsonObject(
        if calls.isEmpty then base
        else
          base.add(
            "tool_calls",
            Json.arr(calls.map { call =>
              Json.obj(
                "id"       -> Json.fromString(call.id),
                "type"     -> Json.fromString("function"),
                "function" -> Json.obj("name" -> Json.fromString(call.name), "arguments" -> Json.fromString(call.rawArguments))
              )
            }*)
          )
      )
    case ChatMessage.Tool(id, name, c) =>
      Json.obj(
        "role"         -> Json.fromString("tool"),
        "tool_call_id" -> Json.fromString(id),
        "name"         -> Json.fromString(name),
        "content"      -> Json.fromString(c)
      )

  def tool(t: ToolSpec): Json =
    Json.obj(
      "type" -> Json.fromString("function"),
      "function" -> Json.obj(
        "name"        -> Json.fromString(t.name),
        "description" -> Json.fromString(t.description),
        "parameters" -> Json.obj(
          "type" -> Json.fromString("object"),
          "properties" -> Json.fromFields(t.params.map { p =>
            p.name -> Json.obj("type" -> Json.fromString(p.tpe.jsonSchemaName), "description" -> Json.fromString(p.description))
          }),
          "required" -> Json.arr(t.params.filter(_.required).map(p => Json.fromString(p.name))*)
        )
      )
    )

  /** Đọc `choices[0].message` của câu trả lời. */
  def reply(body: String): Either[String, LlmReply] =
    for
      json <- parse(body).left.map(_.getMessage)
      msg  <- json.hcursor.downField("choices").downN(0).downField("message").focus.toRight(s"Không thấy choices[0].message: $body")
      c     = msg.hcursor
      content = c.downField("content").as[Option[String]].toOption.flatten
      calls = c.downField("tool_calls").values.toList.flatten.map { tc =>
                val h    = tc.hcursor
                val raw  = h.downField("function").downField("arguments").as[String].getOrElse("{}")
                ToolCall(
                  id = h.downField("id").as[String].getOrElse(""),
                  name = h.downField("function").downField("name").as[String].getOrElse(""),
                  arguments = arguments(raw),
                  rawArguments = raw
                )
              }
    yield LlmReply(content, calls)

  /** JSON tham số → Map. Giá trị không phải chuỗi thì giữ dạng JSON rút gọn. */
  def arguments(raw: String): Map[String, String] =
    parse(raw).toOption.flatMap(_.asObject).fold(Map.empty[String, String]) { obj =>
      obj.toMap.view.mapValues(v => v.asString.getOrElse(v.noSpaces)).toMap
    }
