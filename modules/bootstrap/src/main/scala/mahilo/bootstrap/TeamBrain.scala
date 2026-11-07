package mahilo.bootstrap

import cats.effect.IO
import mahilo.adapters.local.ScriptedLlmClient.Script.*
import mahilo.application.port.out.{LlmReply, LlmRequest}
import mahilo.domain.conversation.ChatMessage

/** "Bộ não" giả lập cho ví dụ team_of_agents. Quyết định theo: agent nào, tin đến từ
  * người dùng hay từ hàng đợi, và tool nào vừa chạy xong.
  *
  * Kịch bản 1 (Sales ↔ Product):
  *   người dùng → SalesAgent "phân tích các cuộc gọi" → collect_feature_feedback → trả lời
  *   người dùng → SalesAgent "hỏi product đi"        → chat_with_agent(ProductAgent)
  *   ProductAgent (hàng đợi) → analyze_feature_request → chat_with_agent(SalesAgent)
  *   SalesAgent  (hàng đợi) → contact_human
  *
  * Kịch bản 2 (Marketing ↔ Sales):
  *   người dùng → MarketingAgent "có tính năng mới" → research_trending_topics → generate_content_calendar
  *                → chat_with_agent(SalesAgent, "kênh nào nhiều lead?")
  *   SalesAgent (hàng đợi) → analyze_lead_sources → chat_with_agent(MarketingAgent)
  *   MarketingAgent (hàng đợi) → contact_human(đề xuất)
  */
object TeamBrain:

  private val Pending = "Pending messages: "

  final case class Turn(agent: String, fromQueue: Boolean, sender: String, payload: String, human: String, lastTool: Option[(String, String)])

  def turn(req: LlmRequest): Turn =
    val text = lastUserText(req)
    val q    = text.startsWith(Pending)
    val (sender, payload) =
      if q then text.stripPrefix(Pending).split(": ", 2) match
        case Array(s, p) => (s, p)
        case other       => ("", other.mkString)
      else ("", "")
    val lastTool = last(req).collect { case ChatMessage.Tool(_, name, content) => name -> content }
    Turn(agentName(req).getOrElse(""), q, sender, payload, text.split("User: ").last, lastTool)

  def handles(agent: Option[String]): Boolean =
    agent.exists(Set("SalesAgent", "MarketingAgent", "ProductAgent"))

  def reply(req: LlmRequest): IO[LlmReply] =
    val t = turn(req)
    (t.agent, t.fromQueue, t.lastTool.map(_._1)) match

      // ---------- SalesAgent ----------
      case ("SalesAgent", false, None) =>
        val h = t.human.toLowerCase
        if h.contains("product") then
          call("chat_with_agent", "agent_name" -> "ProductAgent", "question" -> "What is the status of the mahilo integration feature? Customers are asking for it.")
        else if h.contains("call") || h.contains("cuộc gọi") then call("collect_feature_feedback")
        else if h.contains("insight") then call("generate_sales_insights")
        else say("Mình có thể phân tích các cuộc gọi, nguồn lead, hoặc đưa ra insight bán hàng.")
      case ("SalesAgent", false, Some("collect_feature_feedback")) =>
        say(s"Kết quả phân tích: ${t.lastTool.get._2} Bạn có muốn mình hỏi ProductAgent về tình trạng tính năng mahilo integration không?")
      case ("SalesAgent", false, Some("chat_with_agent")) =>
        say("Mình đã hỏi ProductAgent, sẽ báo lại khi có trả lời.")
      case ("SalesAgent", false, Some(_)) => say(t.lastTool.get._2)
      case ("SalesAgent", true, None) if t.sender == "MarketingAgent" => call("analyze_lead_sources")
      case ("SalesAgent", true, None) => call("contact_human", "message" -> s"${t.sender} trả lời: ${t.payload}")
      case ("SalesAgent", true, Some("analyze_lead_sources")) =>
        call("chat_with_agent", "agent_name" -> "MarketingAgent", "question" -> s"Lead sources: ${t.lastTool.get._2}")
      case ("SalesAgent", true, Some(_)) => say("Đã xử lý tin từ agent khác.")

      // ---------- ProductAgent ----------
      case ("ProductAgent", _, None) => call("analyze_feature_request", "feature" -> "mahilo integration")
      case ("ProductAgent", true, Some("analyze_feature_request")) =>
        call("chat_with_agent", "agent_name" -> t.sender, "question" -> t.lastTool.get._2)
      case ("ProductAgent", false, Some("analyze_feature_request")) => say(t.lastTool.get._2)
      case ("ProductAgent", _, Some(_)) => say("Đã trả lời agent hỏi về tính năng.")

      // ---------- MarketingAgent ----------
      case ("MarketingAgent", false, None) => call("research_trending_topics", "industry" -> "AI agents")
      case ("MarketingAgent", false, Some("research_trending_topics")) => call("generate_content_calendar", "timeframe" -> "Q1")
      case ("MarketingAgent", false, Some("generate_content_calendar")) =>
        call("chat_with_agent", "agent_name" -> "SalesAgent", "question" -> "Which channels have brought in more leads?")
      case ("MarketingAgent", false, Some(_)) =>
        say("Mình đã lên lịch nội dung (tháng 1: Multi-Agent Capabilities, tháng 2: Integrations) và đang hỏi SalesAgent kênh nào nhiều lead.")
      case ("MarketingAgent", true, None) =>
        call("contact_human", "message" -> s"Đề xuất: đẩy lịch nội dung trên kênh mà SalesAgent báo. Chi tiết: ${t.payload}")
      case ("MarketingAgent", true, Some(_)) => say("Đã gửi đề xuất cho bạn.")

      case _ => say("COMPLIANCE: YES\nREASON: demo")
