package mahilo.adapters.langchain4j

import scala.jdk.CollectionConverters.*
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.model.chat.ChatModel
import mahilo.domain.conversation.ToolCall

/** Những gì một graph cần để chạy MỘT lượt của agent Mahilo. */
final case class GraphParts(
    model: ChatModel,
    tools: List[ToolSpecification],
    /** Chạy tool qua lõi Mahilo (đồng bộ, vì LangGraph4j gọi node trên thread của nó). */
    executeTool: ToolCall => String,
    maxIterations: Int
)
