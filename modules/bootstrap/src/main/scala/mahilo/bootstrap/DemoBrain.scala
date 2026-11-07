package mahilo.bootstrap

import cats.effect.IO
import mahilo.adapters.local.ScriptedLlmClient
import mahilo.adapters.local.ScriptedLlmClient.Script.*
import mahilo.application.port.out.{LlmReply, LlmRequest}
import mahilo.domain.conversation.ChatMessage

/** "Bộ não" giả lập cho kịch bản Dispatcher ↔ Plumber (mục 6.1 trong bản phân tích).
  *
  * Nó đọc system prompt để biết đang đóng vai agent nào, nhìn tin cuối để quyết định gọi
  * tool gì. Không thông minh, nhưng đủ để chạy trọn luồng Mahilo mà không cần API key:
  *
  *   khách ─► dispatcher ─chat_with_agent─► [hàng đợi] ─► plumber ─contact_human─► thợ
  *   thợ  ─► plumber ─chat_with_agent─► [hàng đợi] ─► dispatcher ─contact_human─► khách
  */
object DemoBrain:

  private val Pending = "Pending messages: "

  /** Phần sau "User: " cuối cùng (bỏ ngữ cảnh "Other Conversations" phía trước). */
  private def humanText(turnText: String): String =
    turnText.lastIndexOf("User: ") match
      case -1 => turnText
      case i  => turnText.substring(i + "User: ".length)

  def reply(req: LlmRequest): IO[LlmReply] =
    val turn       = lastUserText(req)
    val fromQueue  = turn.startsWith(Pending)
    val afterTool  = last(req).exists(_.isInstanceOf[ChatMessage.Tool])
    (agentName(req), fromQueue, afterTool) match

      // ---- Dispatcher ----
      case (Some("dispatcher"), false, false) =>
        call("chat_with_agent", "agent_name" -> "plumber", "question" -> s"Customer reports: ${humanText(turn)}. When can you come?")
      case (Some("dispatcher"), false, true) =>
        say("Mình đã liên hệ thợ sửa ống nước, sẽ báo lại bạn ngay khi có phản hồi.")
      case (Some("dispatcher"), true, false) =>
        call("contact_human", "message" -> s"Cập nhật từ thợ: ${turn.stripPrefix(Pending).dropWhile(_ != ':').drop(2)}")
      case (Some("dispatcher"), true, true) =>
        say("Đã báo lại cho khách.")

      // ---- Plumber (proxy cho thợ thật) ----
      case (Some("plumber"), true, false) =>
        call("contact_human", "message" -> s"Điều phối viên hỏi: ${turn.stripPrefix(Pending).dropWhile(_ != ':').drop(2)}")
      case (Some("plumber"), true, true) =>
        say("Đã hỏi thợ, đang chờ trả lời.")
      case (Some("plumber"), false, false) =>
        call("chat_with_agent", "agent_name" -> "dispatcher", "question" -> s"Plumber says: ${humanText(turn)}")
      case (Some("plumber"), false, true) =>
        say("Đã chuyển lời của anh cho điều phối viên.")

      // ---- Mọi trường hợp khác (vd LLM chấm policy) ----
      case _ => say("COMPLIANCE: YES\nREASON: demo")

  val llm: ScriptedLlmClient = ScriptedLlmClient(reply)
