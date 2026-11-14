package mahilo.domain.conversation

/** Một lời gọi tool do LLM yêu cầu.
  *
  * @param id        id do LLM sinh, phải trả lại trong tin `Tool` tương ứng
  * @param name      tên tool
  * @param arguments tham số đã giải mã thành map tên → giá trị (dạng chuỗi)
  * @param rawArguments chuỗi JSON gốc, giữ lại để gửi lại cho LLM đúng như nó đã viết
  */
final case class ToolCall(id: String, name: String, arguments: Map[String, String], rawArguments: String)
