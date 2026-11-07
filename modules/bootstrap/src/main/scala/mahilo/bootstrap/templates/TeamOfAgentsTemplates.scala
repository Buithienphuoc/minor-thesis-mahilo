package mahilo.bootstrap.templates

import cats.effect.IO
import mahilo.application.port.out.AgentEngine
import mahilo.application.service.{AgentDefinition, CustomTool}
import mahilo.domain.AgentName
import mahilo.domain.agent.AgentSpec
import mahilo.domain.tool.{ToolParam, ToolSpec}

/** Ví dụ "team of agents": 3 phòng ban, mỗi agent một engine khác nhau.
  * Python: `examples/team_of_agents/control_plane.py`.
  *
  * | Agent          | Python gốc                  | Scala                                   |
  * |----------------|-----------------------------|-----------------------------------------|
  * | SalesAgent     | `BaseAgent` + tools         | engine gốc (`ToolLoopEngine`)           |
  * | MarketingAgent | `LangGraphAgent`            | `LangGraph4jEngine` (LangChain4j)       |
  * | ProductAgent   | `PydanticAIAgent`           | service Python PydanticAI qua REST      |
  *
  * Tool của cả 3 agent được khai báo ở đây (Scala) và luôn chạy trong lõi Mahilo, dù
  * engine "suy nghĩ" là gì. Dữ liệu trả về giữ nguyên như ví dụ Python (dữ liệu giả).
  */
object TeamOfAgentsTemplates:

  val Sales     = AgentName("SalesAgent")
  val Marketing = AgentName("MarketingAgent")
  val Product   = AgentName("ProductAgent")

  // ---------------- Prompt: chép nguyên từ control_plane.py ----------------

  val productPrompt: String =
    """you only have access to sales and marketing agents. dont make agents up.
      |when asked about the status of a feature, use the analyze_feature_request tool and respond back to the agent that asked you the question.
      |you should use the chat_with_agent function to respond back to an agent that asked you a question.
      |You can then request approvals from your user (human) when you feel there's a need to
      |prioritize a feature. DONT ASK QUESTIONS YOURSELF, this is just a demo, assume stuff when there's not enough information.""".stripMargin

  val marketingPrompt: String =
    """When notified of new features, research trending topics and generate a content calendar. Give your recommendations on what to do, to your user (human).
      |Use the analyze_content_performance tool when asked about the performance of the content. You should also ask the sales agent for what channels have brought in more leads.""".stripMargin

  val salesPrompt: String =
    """When asked about analysing calls, gather user feedback on it and present to your user (human).
      |If you spot a feature that has been requested or has some interest in public, ask your human if you can ask the product agent about its status.
      |For all other scenarios, only call tools or actions when asked to do so. Don't do everything together, wait for prompts. dont call other tools.""".stripMargin

  val sales: AgentSpec     = AgentSpec(Sales, "sales_agent", salesPrompt, "Sales agent")
  val marketing: AgentSpec = AgentSpec(Marketing, "langgraph", marketingPrompt, "Marketing agent")
  val product: AgentSpec   = AgentSpec(Product, "pydantic_ai", productPrompt, "Product agent")

  // ---------------- Tool: mahilo_sales_agent.py ----------------

  private def noArgs(name: String, description: String, result: String): CustomTool =
    CustomTool(ToolSpec(name, description, Nil), _ => IO.pure(result))

  val salesTools: List[CustomTool] = List(
    noArgs("analyze_lead_sources", "Analyze effectiveness of different lead sources",
      "We've been getting more leads from Reddit lately."),
    noArgs("collect_feature_feedback", "Analyse past interactions and collect customer feedback about desired features",
      "I've analyzed last month's calls. 40 percent of all enterprise customers are interested in an integration with mahilo."),
    noArgs("generate_sales_insights", "Generate insights about sales patterns and customer preferences",
      "We should focus on getting more enterprise customers and integrations with mahilo.")
  )

  // ---------------- Tool: langgraph_marketing_agent.py ----------------

  private def oneArg(name: String, description: String, param: String)(run: String => String): CustomTool =
    CustomTool(ToolSpec(name, description, List(ToolParam(param, param))), args => IO.pure(run(args.getOrElse(param, ""))))

  val marketingTools: List[CustomTool] = List(
    oneArg("analyze_content_performance", "Analyze the performance of the content across social media platforms", "content")(
      _ => "The content is performing well on LinkedIn."),
    oneArg("research_trending_topics", "Research current trending topics in the industry", "industry")(
      _ => """["Lot of buzz around mahilo agents and its multi-agent capabilities"]"""),
    oneArg("generate_content_calendar", "Create a content calendar based on trends and performance", "timeframe")(
      _ => """{"January": "Mahilo Agents: Multi-Agent Capabilities", "February": "Integrations with mahilo"}""")
  )

  // ---------------- Tool: pydanticai_product_agent.py ----------------

  val productTools: List[CustomTool] = List(
    oneArg("analyze_feature_request", "Analyze viability and impact of requested features", "feature")(
      _ => "Based on analysis, mahilo integration is in our Q3 roadmap, coming in a few months."),
    noArgs("get_product_roadmap", "Get current product roadmap and timeline", "Build mahilo Agents"),
    oneArg("analyze_usage_patterns", "Analyze how customers are using specific features", "feature")(
      _ => """{"mahilo integration": 0.5, "New feature": 0.3}""")
  )

  /** @param marketingEngine engine cho MarketingAgent (thường là LangGraph4j)
    * @param productEngine   engine cho ProductAgent (thường là PydanticAI qua REST)
    * `None` = dùng engine gốc.
    */
  def definitions(marketingEngine: Option[AgentEngine], productEngine: Option[AgentEngine]): List[AgentDefinition] = List(
    AgentDefinition(product, productTools, productEngine),
    AgentDefinition(marketing, marketingTools, marketingEngine),
    AgentDefinition(sales, salesTools)
  )

  /** control_plane.py bật cả 3 agent ngay khi khởi động. */
  val startActive: List[AgentName] = List(Product, Marketing, Sales)
