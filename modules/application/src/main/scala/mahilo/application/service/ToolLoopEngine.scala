package mahilo.application.service

import cats.effect.IO
import cats.syntax.all.*
import mahilo.application.port.out.*
import mahilo.domain.conversation.ChatMessage
import mahilo.domain.telemetry.TelemetryEvent

/** Vòng lặp tool-calling gốc của Mahilo: gọi LLM → nếu có tool_calls thì chạy từng tool,
  * thêm kết quả, gọi LLM lại → ... cho tới khi LLM trả văn bản.
  *
  * Python viết vòng lặp này HAI lần gần như y hệt (`_process_chat_message_internal` và
  * `_process_queue_message_internal`). Ở đây chỉ có một, và có giới hạn số vòng.
  */
final class ToolLoopEngine(llm: LlmClient, telemetry: Telemetry) extends AgentEngine:

  def run(turn: AgentTurn): IO[TurnResult] =

    def step(produced: Vector[ChatMessage], iteration: Int): IO[TurnResult] =
      if iteration >= turn.maxIterations then
        IO.pure(TurnResult(Some("(Đã dừng: quá số vòng gọi tool cho phép)"), produced, stoppedEarly = true))
      else
        callLlm(produced).flatMap { reply =>
          val withReply = produced :+ reply.asMessage
          if reply.toolCalls.isEmpty then IO.pure(TurnResult(reply.content, withReply, stoppedEarly = false))
          else
            // Mỗi tool_call LUÔN có đúng một tin `Tool` trả lời, kể cả khi tool lỗi
            // (Python `continue` khi lỗi, làm thiếu tool result và provider từ chối request).
            reply.toolCalls
              .traverse(call => turn.executeTool(call).map(out => ChatMessage.Tool(call.id, call.name, out)))
              .flatMap(results => step(withReply ++ results, iteration + 1))
        }

    def callLlm(produced: Vector[ChatMessage]): IO[LlmReply] =
      llm
        .complete(LlmRequest(turn.messages ++ produced, turn.tools))
        .timed
        .flatMap { (took, reply) =>
          telemetry.record(TelemetryEvent.LlmCalled(turn.agent.name, took.toMillis)).as(reply)
        }

    step(Vector.empty, 0)
