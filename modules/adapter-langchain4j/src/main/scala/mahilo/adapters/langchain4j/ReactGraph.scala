package mahilo.adapters.langchain4j

import java.util
import scala.jdk.CollectionConverters.*
import dev.langchain4j.data.message.{AiMessage, ChatMessage as LcMessage, ToolExecutionResultMessage}
import dev.langchain4j.model.chat.request.ChatRequest
import org.bsc.langgraph4j.StateGraph
import org.bsc.langgraph4j.GraphDefinition.{END, START}
import org.bsc.langgraph4j.action.{AsyncEdgeAction, AsyncNodeAction}
import org.bsc.langgraph4j.langchain4j.serializer.std.LC4jStateSerializer

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
