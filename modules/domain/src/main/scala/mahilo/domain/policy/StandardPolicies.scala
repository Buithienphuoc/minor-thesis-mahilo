package mahilo.domain.policy

import mahilo.domain.messaging.Envelope

/** 4 policy mặc định, đều TẮT sẵn. Python: `create_default_policies()`. */
object StandardPolicies:

  /** Chặn agent lặp lại chính mình hoặc rơi vào vòng ping-pong A→B→A→B. */
  val antiLoop: Policy = Policy(
    name = "anti_loop",
    description = "Prevents agents from falling into repetitive conversation patterns",
    rule = PolicyRule.Heuristic(antiLoopCheck),
    priority = 90
  )

  def antiLoopCheck(message: Envelope, ctx: PolicyContext): Option[String] =
    val history = ctx.history // cũ → mới
    if history.size < 4 then None
    else
      val lastFromSender = history.filter(_.sender == message.sender).lastOption
      val tooSimilar     = lastFromSender.exists(prev => TextSimilarity.ratio(message.payload, prev.payload) > 0.8)

      lazy val pingPong =
        history.takeRight(4) match
          case List(m1, m2, m3, m4) =>
            val pattern = m1.sender == message.sender && m2.sender == message.recipient &&
              m3.sender == message.sender && m4.sender == message.recipient
            pattern &&
            TextSimilarity.ratio(m1.payload, m3.payload) > 0.7 &&
            TextSimilarity.ratio(m2.payload, m4.payload) > 0.7
          case _ => false

      if tooSimilar then
        Some("Your message is too similar to your previous message. Please provide new information or a different approach.")
      else if pingPong then
        Some("It seems you're in a repetitive conversation pattern. Try a different approach or provide new information.")
      else None

  /** Tin không quá ngắn (< 10 ký tự) và không quá dài (> 4000 ký tự). */
  val messageLength: Policy = Policy(
    name = "message_length",
    description = "Ensures messages aren't too long or too short",
    rule = PolicyRule.Heuristic { (message, _) =>
      if message.payload.trim.length < 10 then Some("Your message is too short. Please provide more information.")
      else if message.payload.length > 4000 then Some("Your message is too long. Please be more concise.")
      else None
    },
    priority = 50
  )

  val relevance: Policy = Policy(
    name = "relevance",
    description = "Ensures messages are relevant to the task or topic",
    rule = PolicyRule.NaturalLanguage(
      "The message must be relevant to the current task or topic of conversation. " +
        "It should not introduce completely unrelated topics without clear justification."
    ),
    priority = 70
  )

  val toxicity: Policy = Policy(
    name = "toxicity",
    description = "Prevents harmful or inappropriate content",
    rule = PolicyRule.NaturalLanguage(
      "The message must not contain harmful, offensive, or inappropriate content. " +
        "This includes but is not limited to: hate speech, personal attacks, explicit content, " +
        "or anything that could be considered harmful to individuals or groups."
    ),
    priority = 100
  )

  val all: PolicySet = PolicySet(antiLoop, messageLength, relevance, toxicity)
