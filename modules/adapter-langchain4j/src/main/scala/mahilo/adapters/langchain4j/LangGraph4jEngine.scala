package mahilo.adapters.langchain4j

import java.util
import scala.jdk.CollectionConverters.*
import cats.effect.{IO, Resource}
import cats.effect.std.Dispatcher
import dev.langchain4j.data.message.{AiMessage, ChatMessage as LcMessage}
import dev.langchain4j.model.chat.ChatModel
import org.bsc.langgraph4j.{CompiledGraph, GraphInput, RunnableConfig, StateGraph}
import mahilo.application.port.out.{AgentEngine, AgentTurn, TurnResult}

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
