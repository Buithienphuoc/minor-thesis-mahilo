package mahilo.domain.policy

import mahilo.domain.messaging.Envelope

/** Phần THUẦN của việc nhờ LLM chấm policy viết bằng lời: dựng prompt và đọc kết quả.
  * Phần gọi LLM thật nằm ở tầng application. Python: nửa sau của `Policy.evaluate`.
  */
object NaturalLanguageJudge:

  def prompt(policyText: String, message: Envelope, ctx: PolicyContext): String =
    val history =
      if ctx.history.isEmpty then "(trống)"
      else ctx.history.map(e => s"- ${e.sender.value} -> ${e.recipient.value}: ${e.payload}").mkString("\n")
    s"""You are evaluating if a message complies with a policy.
       |
       |POLICY: $policyText
       |
       |MESSAGE FROM: ${message.sender.value}
       |MESSAGE TO: ${message.recipient.value}
       |MESSAGE CONTENT: ${message.payload}
       |
       |RECENT CONVERSATION (oldest first):
       |$history
       |
       |Does this message comply with the policy? Answer with YES or NO, followed by your reasoning.
       |Be strict in your evaluation. If there's any doubt, the message should not comply.
       |
       |Format your response exactly as:
       |COMPLIANCE: YES/NO
       |REASON: Your detailed reasoning here""".stripMargin

  private val Reason = """(?is)REASON:\s*(.*?)(?:\n|$)""".r.unanchored

  /** `None` = tuân thủ, `Some(lý do)` = vi phạm. Giữ đúng thứ tự kiểm tra của Python. */
  def parse(response: String): Option[String] =
    val upper = response.toUpperCase
    if upper.contains("COMPLIANCE: YES") then None
    else if upper.contains("COMPLIANCE: NO") then
      response match
        case Reason(r) if r.trim.nonEmpty => Some(r.trim)
        case _                            => Some("Message violates policy")
    else if response.toLowerCase.startsWith("yes") then None
    else
      val rest = response.linesIterator.drop(1).mkString(" ").trim
      Some(if rest.nonEmpty then rest else "Message violates policy")
