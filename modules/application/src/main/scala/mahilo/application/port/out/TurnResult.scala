package mahilo.application.port.out

import mahilo.domain.conversation.ChatMessage

/** @param reply        câu trả lời văn bản cuối cùng
  * @param newMessages  các tin assistant/tool sinh ra trong lượt (để lưu vào session)
  * @param stoppedEarly true nếu bị cắt vì vượt `maxIterations`
  */
final case class TurnResult(reply: Option[String], newMessages: Vector[ChatMessage], stoppedEarly: Boolean)
