package mahilo.adapters.local

import cats.effect.IO
import mahilo.application.port.out.{LlmClient, LlmReply, LlmRequest}

/** ADAPTER cho port `LlmClient` KHÔNG cần mạng: câu trả lời do một hàm Scala quyết định.
  *
  * Dùng để chạy demo và test mà không cần API key. Nhờ kiến trúc lục giác, lõi không
  * phân biệt được đây là LLM "giả" hay OpenAI thật.
  */
final class ScriptedLlmClient(script: LlmRequest => IO[LlmReply]) extends LlmClient:
  def complete(request: LlmRequest): IO[LlmReply] = script(request)
