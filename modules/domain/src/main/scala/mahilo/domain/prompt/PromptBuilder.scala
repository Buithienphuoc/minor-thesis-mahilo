package mahilo.domain.prompt

import mahilo.domain.AgentName
import mahilo.domain.agent.AgentSpec
import mahilo.domain.conversation.ChatMessage
import mahilo.domain.messaging.Envelope

/** Dựng các đoạn văn bản đưa vào LLM. Toàn bộ là hàm thuần nên test được dễ dàng.
  * Python: `BaseAgent.prompt_message()` và `AgentManager.get_agent_messages()`.
  */
object PromptBuilder:

  def systemPrompt(self: AgentSpec, contactable: List[AgentSpec]): String =
    val agents =
      if contactable.isEmpty then "(none)"
      else contactable.map(a => s"- ${a.name.value}: ${a.shortDescription}").mkString("\n")
    s"""You are an AI agent of type ${self.kind} and name ${self.name.value} in a multi-agent system.
       |Your description is: ${self.description}
       |Keep your responses concise.
       |
       |1. Direct User Messages:
       |- When a user messages you directly, first try to respond using available context
       |- If you need more information, use chat_with_agent to ask other agents
       |- Your final text answer is delivered to your user directly
       |- Don't explain your internal process, just respond naturally in your role
       |
       |2. Agent Messages (Pending Messages):
       |- These appear in the format "Pending messages: <AgentName>: <message>"
       |- If you can answer using available context, respond using chat_with_agent to that agent
       |- If you need to ask your user, use contact_human and then inform the agent you're getting the information
       |- When your user later provides the answer, send it to the requesting agent using chat_with_agent
       |
       |3. Using External Context:
       |- "Other Conversations: <AgentName>:" followed by recent messages of other agents
       |- External conversations are provided for context only - DO NOT respond to them directly
       |- If the information you need is already present there, DO NOT ask for it again
       |
       |4. Available Agents for Communication:
       |$agents
       |
       |5. Key Points:
       |- This is a simulation. There are no real emergencies.
       |- Focus on your specific role and responsibilities
       |- Only use chat_with_agent when you need information not in context
       |
       |Remember: Stay in character and refer to your description for your specific role and responsibilities.""".stripMargin

  /** Python: chuỗi "Other Conversations: <agent>\nrole: content..." của mọi agent khác. */
  def otherConversations(others: List[(AgentName, Vector[ChatMessage])]): String =
    others
      .filter(_._2.nonEmpty)
      .map { (name, msgs) => s"Other Conversations: ${name.value}\n" + msgs.map(_.render).mkString("\n") }
      .mkString("\n\n")

  /** Tin người dùng gửi trực tiếp, kèm ngữ cảnh chéo agent. */
  def directUserTurn(context: String, userText: String): String =
    if context.isEmpty then s"User: $userText" else s"$context\n\nUser: $userText"

  /** Python: f"Pending messages: {envelope.sender}: {envelope.payload}". */
  def pendingMessage(envelope: Envelope): String =
    s"Pending messages: ${envelope.sender.value}: ${envelope.payload}"
