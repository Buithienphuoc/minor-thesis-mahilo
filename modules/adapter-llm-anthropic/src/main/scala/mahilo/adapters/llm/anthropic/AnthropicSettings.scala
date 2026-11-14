package mahilo.adapters.llm.anthropic

/** Cấu hình gửi kèm mỗi request của adapter Claude (đọc từ biến môi trường ở `ServerMain`). */
final case class AnthropicSettings(
    model: String = "claude-opus-5-5",
    maxTokens: Long = 16000L,
    effort: Option[String] = None, // low | medium | high | xhigh | max; None = mặc định của model
    fallbacks: Boolean = true      // khi Claude từ chối vì bộ lọc an toàn, server tự thử model dự phòng
)
