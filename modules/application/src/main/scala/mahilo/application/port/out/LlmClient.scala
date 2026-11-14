package mahilo.application.port.out

import cats.effect.IO

/** PORT RA: "tôi cần một bộ não biết gọi tool".
  *
  * Python gọi thẳng singleton `llm_config` (LiteLLM). Ở đây application chỉ biết trait
  * này; adapter OpenAI, adapter giả lập (cho test/demo)... đều hiện thực nó.
  */
trait LlmClient:
  def complete(request: LlmRequest): IO[LlmReply]
