package mahilo.adapters.langchain4j

import java.util
import scala.jdk.CollectionConverters.*
import cats.effect.{IO, Resource}
import cats.effect.std.Dispatcher
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.{AiMessage, ChatMessage as LcMessage, ToolExecutionResultMessage}
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import org.bsc.langgraph4j.{CompiledGraph, GraphInput, RunnableConfig, StateGraph}
import org.bsc.langgraph4j.GraphDefinition.{END, START}
import org.bsc.langgraph4j.action.{AsyncEdgeAction, AsyncNodeAction}
import org.bsc.langgraph4j.langchain4j.serializer.std.LC4jStateSerializer
import org.bsc.langgraph4j.state.{AgentState, Channel, Channels}
import mahilo.application.port.out.{AgentEngine, AgentTurn, TurnResult}
import mahilo.domain.conversation.ToolCall

/** State của graph: một kênh `messages` (nối thêm) và một bộ đếm `steps`.
  * Python (LangGraph): `MessagesState` / `{"messages": [...]}`.
  */
final class MahiloGraphState(data: util.Map[String, Object]) extends AgentState(data):
  def messages: List[LcMessage] =
    this.value[util.List[LcMessage]](MahiloGraphState.Messages).map(_.asScala.toList).orElse(Nil)
  def steps: Int = this.value[Integer](MahiloGraphState.Steps).map(_.intValue).orElse(0)

object MahiloGraphState:
  val Messages = "messages"
  val Steps    = "steps"

  /** `appenderWithDuplicate`: hai tin giống hệt nhau (vd hai kết quả tool trùng chữ) vẫn được giữ. */
  val schema: util.Map[String, Channel[?]] = Map[String, Channel[?]](
    Messages -> Channels.appenderWithDuplicate[LcMessage](() => new util.ArrayList[LcMessage]()),
    Steps    -> Channels.base[Integer](() => Integer.valueOf(0))
  ).asJava

/** Những gì một graph cần để chạy MỘT lượt của agent Mahilo. */
final case class GraphParts(
    model: ChatModel,
    tools: List[ToolSpecification],
    /** Chạy tool qua lõi Mahilo (đồng bộ, vì LangGraph4j gọi node trên thread của nó). */
    executeTool: ToolCall => String,
    maxIterations: Int
)

/** Graph ReAct mặc định: agent ⇄ tools, giống `create_react_agent`/`ToolNode` +
  * `tools_condition` của LangGraph Python (xem examples/team_of_agents/langgraph_marketing_agent.py).
  *
  * {{{
  *   START ─► agent ──(có tool call)──► tools ─┐
  *              ▲                               │
  *              └───────────────────────────────┘
  *            agent ──(không còn tool call / quá số vòng)──► END
  * }}}
  */
object ReactGraph:
  import LangChain4jConversions.toolCall

  def build(parts: GraphParts): StateGraph[MahiloGraphState] =
    val agent: AsyncNodeAction[MahiloGraphState] = AsyncNodeAction.node_async { (s: MahiloGraphState) =>
      val req = ChatRequest.builder().messages(s.messages.asJava)
      val ai  = parts.model.chat(if parts.tools.isEmpty then req.build() else req.toolSpecifications(parts.tools.asJava).build()).aiMessage()
      Map[String, Object](
        MahiloGraphState.Messages -> util.List.of[LcMessage](ai),
        MahiloGraphState.Steps    -> Integer.valueOf(s.steps + 1)
      ).asJava
    }

    val tools: AsyncNodeAction[MahiloGraphState] = AsyncNodeAction.node_async { (s: MahiloGraphState) =>
      val results: List[LcMessage] = lastAi(s).toList.flatMap(_.toolExecutionRequests().asScala).map { r =>
        ToolExecutionResultMessage.from(r, parts.executeTool(toolCall(r)))
      }
      Map[String, Object](MahiloGraphState.Messages -> new util.ArrayList[LcMessage](results.asJava)).asJava
    }

    val route: AsyncEdgeAction[MahiloGraphState] = AsyncEdgeAction.edge_async { (s: MahiloGraphState) =>
      val wantsTools = lastAi(s).exists(_.hasToolExecutionRequests())
      if wantsTools && s.steps < parts.maxIterations then "tools" else "end"
    }

    // LangGraph4j sao chép (clone) state sau mỗi node bằng serializer. Message của
    // LangChain4j không `Serializable`, nên phải dùng serializer riêng LC4jStateSerializer.
    val serializer = LC4jStateSerializer[MahiloGraphState]((data: util.Map[String, Object]) => MahiloGraphState(data))
    StateGraph[MahiloGraphState](MahiloGraphState.schema, serializer)
      .addNode("agent", agent)
      .addNode("tools", tools)
      .addEdge(START, "agent")
      .addConditionalEdges("agent", route, Map("tools" -> "tools", "end" -> END).asJava)
      .addEdge("tools", "agent")

  private def lastAi(s: MahiloGraphState): Option[AiMessage] = s.messages.lastOption.collect { case a: AiMessage => a }

/** ADAPTER cho port `AgentEngine`: chạy lượt của agent bằng một `StateGraph` LangGraph4j.
  * Python: `mahilo/integrations/langgraph/agent.py::LangGraphAgent`.
  *
  * Khác Python:
  *  - tool `chat_with_agent`/`contact_human` trong graph gọi ngược về lõi Mahilo (qua
  *    `AgentTurn.executeTool`), nên vẫn qua broker, policy, telemetry như agent thường;
  *  - tin trong hàng đợi được xử lý bình thường (bản Python `return` sớm khi không có
  *    tham số `message`, nên agent LangGraph không bao giờ xử lý hộp thư).
  *
  * @param buildGraph cách dựng graph; mặc định là ReAct. Truyền graph riêng để có
  *                   luồng phức tạp hơn (nhiều node, rẽ nhánh, human-in-the-loop...).
  */
final class LangGraph4jEngine private (
    model: ChatModel,
    dispatcher: Dispatcher[IO],
    buildGraph: GraphParts => StateGraph[MahiloGraphState]
) extends AgentEngine:
  import LangChain4jConversions.*

  def run(turn: AgentTurn): IO[TurnResult] =
    val initial = turn.messages.map(toLc)
    val parts = GraphParts(
      model = model,
      tools = turn.tools.map(toolSpec),
      // Cầu nối IO → đồng bộ: node của LangGraph4j chạy trên thread riêng (đã ở trong IO.blocking).
      executeTool = call => dispatcher.unsafeRunSync(turn.executeTool(call)),
      maxIterations = turn.maxIterations
    )
    IO.blocking {
      val graph: CompiledGraph[MahiloGraphState] = buildGraph(parts).compile()
      val input = Map[String, Object](MahiloGraphState.Messages -> new util.ArrayList[LcMessage](initial.asJava)).asJava
      // Python: config = {"configurable": {"thread_id": ...}}
      val config = RunnableConfig.builder().threadId(turn.agent.name.value).build()
      graph.invoke(GraphInput.args(input), config).orElseThrow()
    }.map { finalState =>
      val produced = finalState.messages.drop(initial.size).map(fromLc).toVector
      val lastAi   = finalState.messages.lastOption.collect { case a: AiMessage => a }
      TurnResult(
        reply = lastAi.flatMap(a => Option(a.text()).filter(_.nonEmpty)),
        newMessages = produced,
        stoppedEarly = lastAi.exists(_.hasToolExecutionRequests())
      )
    }

object LangGraph4jEngine:
  def resource(
      model: ChatModel,
      buildGraph: GraphParts => StateGraph[MahiloGraphState] = ReactGraph.build
  ): Resource[IO, LangGraph4jEngine] =
    Dispatcher.parallel[IO](await = true).map(d => LangGraph4jEngine(model, d, buildGraph))
