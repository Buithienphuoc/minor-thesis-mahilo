package mahilo.adapters.persistence.sqlite

import cats.effect.IO
import io.circe.{Decoder, Encoder, Json}
import io.circe.parser.decode
import io.circe.syntax.*
import mahilo.application.port.out.SessionRepository
import mahilo.domain.AgentName
import mahilo.domain.conversation.{ChatMessage, Session, ToolCall}

/** ADAPTER cho port `SessionRepository` bằng SQLite: mỗi agent một hàng, cột `messages`
  * là mảng JSON. Python: file `sessions/<server_id>/<TYPE>.json` (JSONL).
  */
final class SqliteSessionRepository private (db: Sqlite) extends SessionRepository:
  import SqliteSessionRepository.given

  def load(agent: AgentName): IO[Session] =
    db.query("SELECT messages FROM sessions WHERE agent = ?")(_.setString(1, agent.value))(_.getString(1)).flatMap {
      case Nil => IO.pure(Session.empty(agent))
      case json :: _ =>
        IO.fromEither(decode[Vector[ChatMessage]](json)).map(Session(agent, _))
    }

  def save(session: Session): IO[Unit] =
    db.update("INSERT INTO sessions(agent, messages) VALUES (?, ?) ON CONFLICT(agent) DO UPDATE SET messages = excluded.messages") { st =>
      st.setString(1, session.agent.value)
      st.setString(2, session.messages.asJson.noSpaces)
    }.void

object SqliteSessionRepository:
  def make(path: String): IO[SqliteSessionRepository] =
    val db = Sqlite(path)
    db.update("CREATE TABLE IF NOT EXISTS sessions (agent TEXT PRIMARY KEY, messages TEXT NOT NULL)")().as(SqliteSessionRepository(db))

  // Codec JSON cho ChatMessage. Chỉ adapter này cần, nên domain không phụ thuộc circe.
  given Encoder[ToolCall] = Encoder.forProduct3("id", "name", "arguments")(t => (t.id, t.name, t.rawArguments))
  given Decoder[ToolCall] = Decoder.forProduct3[ToolCall, String, String, String]("id", "name", "arguments") { (id, n, raw) =>
    val args = io.circe.parser.parse(raw).toOption.flatMap(_.asObject)
      .fold(Map.empty[String, String])(_.toMap.view.mapValues(v => v.asString.getOrElse(v.noSpaces)).toMap)
    ToolCall(id, n, args, raw)
  }

  given Encoder[ChatMessage] = Encoder.instance {
    case ChatMessage.System(c)          => Json.obj("role" -> "system".asJson, "content" -> c.asJson)
    case ChatMessage.User(c)            => Json.obj("role" -> "user".asJson, "content" -> c.asJson)
    case ChatMessage.Assistant(c, tc)   => Json.obj("role" -> "assistant".asJson, "content" -> c.asJson, "tool_calls" -> tc.asJson)
    case ChatMessage.Tool(id, name, c)  =>
      Json.obj("role" -> "tool".asJson, "tool_call_id" -> id.asJson, "name" -> name.asJson, "content" -> c.asJson)
  }

  given Decoder[ChatMessage] = Decoder.instance { c =>
    c.downField("role").as[String].flatMap {
      case "system"    => c.downField("content").as[String].map(ChatMessage.System(_))
      case "user"      => c.downField("content").as[String].map(ChatMessage.User(_))
      case "assistant" =>
        for
          content <- c.downField("content").as[Option[String]]
          calls   <- c.downField("tool_calls").as[Option[List[ToolCall]]]
        yield ChatMessage.Assistant(content, calls.getOrElse(Nil))
      case _ =>
        for
          id      <- c.downField("tool_call_id").as[String]
          name    <- c.downField("name").as[String]
          content <- c.downField("content").as[String]
        yield ChatMessage.Tool(id, name, content)
    }
  }
