package mahilo.adapters.remote

import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import io.circe.syntax.*
import mahilo.application.port.out.{AgentTurn, TurnResult}
import mahilo.domain.conversation.{ChatMessage, ToolCall}
import mahilo.domain.tool.ToolSpec

/** JSON của hợp đồng REST v1 (docs/remote-engine-contract.md). Tách khỏi HTTP để dễ test. */
object RemoteEngineJson:

  def turnRequest(turnId: String, turn: AgentTurn, callbackUrl: String): Json = Json.obj(
    "turn_id"        -> turnId.asJson,
    "agent"          -> Json.obj("name" -> turn.agent.name.value.asJson, "kind" -> turn.agent.kind.asJson),
    "messages"       -> Json.arr(turn.messages.map(message)*),
    "tools"          -> Json.arr(turn.tools.map(tool)*),
    "max_iterations" -> turn.maxIterations.asJson,
    "callback"       -> Json.obj("url" -> callbackUrl.asJson)
  )

  def message(m: ChatMessage): Json = m match
    case ChatMessage.System(c) => Json.obj("role" -> "system".asJson, "content" -> c.asJson)
    case ChatMessage.User(c)   => Json.obj("role" -> "user".asJson, "content" -> c.asJson)
    case ChatMessage.Assistant(c, calls) =>
      Json.obj(
        "role"    -> "assistant".asJson,
        "content" -> c.asJson,
        "tool_calls" -> Json.arr(calls.map(tc =>
          Json.obj("id" -> tc.id.asJson, "name" -> tc.name.asJson, "arguments" -> tc.rawArguments.asJson)
        )*)
      )
    case ChatMessage.Tool(id, name, c) =>
      Json.obj("role" -> "tool".asJson, "tool_call_id" -> id.asJson, "name" -> name.asJson, "content" -> c.asJson)

  def tool(t: ToolSpec): Json = Json.obj(
    "name"        -> t.name.asJson,
    "description" -> t.description.asJson,
    "parameters" -> Json.obj(
      "type" -> "object".asJson,
      "properties" -> Json.fromFields(t.params.map(p =>
        p.name -> Json.obj("type" -> p.tpe.jsonSchemaName.asJson, "description" -> p.description.asJson)
      )),
      "required" -> t.params.filter(_.required).map(_.name).asJson
    )
  )

  /** TurnResponse → TurnResult. Nhật ký tool được chép vào session theo dạng chuẩn:
    * một tin assistant chứa các tool call, các tin tool, rồi câu trả lời cuối.
    */
  def turnResult(body: String): Either[String, TurnResult] =
    for
      json  <- parse(body).left.map(_.getMessage)
      c      = json.hcursor
      reply  = c.downField("reply").as[Option[String]].toOption.flatten
      early  = c.downField("stopped_early").as[Boolean].getOrElse(false)
      calls  = c.downField("tool_calls").values.toList.flatten.map { t =>
                 val h   = t.hcursor
                 val raw = h.downField("arguments").as[String].getOrElse("{}")
                 val call = ToolCall(h.downField("id").as[String].getOrElse(""), h.downField("name").as[String].getOrElse(""), arguments(raw), raw)
                 call -> h.downField("result").as[String].getOrElse("")
               }
    yield
      val traced =
        if calls.isEmpty then Vector.empty
        else (ChatMessage.Assistant(None, calls.map(_._1)) +: calls.map((tc, r) => ChatMessage.Tool(tc.id, tc.name, r))).toVector
      TurnResult(reply, traced :+ ChatMessage.Assistant(reply), early)

  /** Body của callback: {"tool_call_id": "...", "arguments": {...}}. */
  def invocation(toolName: String, body: Json): ToolCall =
    val c    = body.hcursor
    val args = c.downField("arguments").focus.flatMap(_.asObject).getOrElse(JsonObject.empty)
    ToolCall(
      id = c.downField("tool_call_id").as[String].getOrElse(""),
      name = toolName,
      arguments = args.toMap.view.mapValues(v => v.asString.getOrElse(v.noSpaces)).toMap,
      rawArguments = Json.fromJsonObject(args).noSpaces
    )

  private def arguments(raw: String): Map[String, String] =
    parse(raw).toOption.flatMap(_.asObject).fold(Map.empty)(_.toMap.view.mapValues(v => v.asString.getOrElse(v.noSpaces)).toMap)
