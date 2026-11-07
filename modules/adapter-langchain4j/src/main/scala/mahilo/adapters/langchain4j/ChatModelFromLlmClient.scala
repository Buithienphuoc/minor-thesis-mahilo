package mahilo.adapters.langchain4j

import scala.jdk.CollectionConverters.*
import cats.effect.{IO, Resource}
import cats.effect.std.Dispatcher
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.model.chat.request.ChatRequest
import dev.langchain4j.model.chat.response.ChatResponse
import mahilo.application.port.out.{LlmClient, LlmRequest}
import mahilo.domain.tool.ToolSpec

/** Chiều ngược lại: biến một `LlmClient` của Mahilo thành `ChatModel` của LangChain4j.
  *
  * Dùng để chạy graph LangGraph4j với LLM giả lập (`ScriptedLlmClient`) trong demo và
  * test, không cần API key. Tham số của tool không được chuyển (kịch bản không cần).
  */
final class ChatModelFromLlmClient private (llm: LlmClient, dispatcher: Dispatcher[IO]) extends ChatModel:
  import LangChain4jConversions.*

  override def doChat(request: ChatRequest): ChatResponse =
    val tools = Option(request.toolSpecifications()).map(_.asScala.toList).getOrElse(Nil)
      .map(t => ToolSpec(t.name(), Option(t.description()).getOrElse(""), Nil))
    val reply = dispatcher.unsafeRunSync(llm.complete(LlmRequest(request.messages().asScala.toList.map(fromLc), tools)))
    val reqs  = reply.toolCalls.map(c => ToolExecutionRequest.builder().id(c.id).name(c.name).arguments(c.rawArguments).build())
    val ai =
      if reqs.isEmpty then AiMessage.from(reply.content.getOrElse(""))
      else reply.content.fold(AiMessage.from(reqs.asJava))(t => AiMessage.from(t, reqs.asJava))
    ChatResponse.builder().aiMessage(ai).build()

object ChatModelFromLlmClient:
  def resource(llm: LlmClient): Resource[IO, ChatModel] =
    Dispatcher.parallel[IO](await = true).map(ChatModelFromLlmClient(llm, _))
