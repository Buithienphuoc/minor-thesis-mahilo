package mahilo.domain.conversation

/** Một tin trong hội thoại, theo đúng 4 vai của OpenAI Chat Completions.
  *
  * Python dùng `Dict[str, Any]` (`{"role": ..., "content": ...}`), nên chỗ nào cũng phải
  * nhớ khóa nào có, khóa nào không. ADT dưới đây làm điều đó thành kiểu.
  */
enum ChatMessage:
  case System(content: String)
  case User(content: String)
  case Assistant(content: Option[String], toolCalls: List[ToolCall] = Nil)
  case Tool(toolCallId: String, toolName: String, content: String)

  def role: String = this match
    case _: System    => "system"
    case _: User      => "user"
    case _: Assistant => "assistant"
    case _: Tool      => "tool"

  /** Dạng một dòng để nhét vào prompt của agent khác ("Other Conversations"). */
  def render: String = this match
    case System(c)          => s"system: $c"
    case User(c)            => s"user: $c"
    case Assistant(c, Nil)  => s"assistant: ${c.getOrElse("")}"
    case Assistant(c, calls) =>
      val called = calls.map(_.name).mkString(", ")
      s"assistant: ${c.getOrElse("")} [gọi tool: $called]".replace(":  [", ": [")
    case Tool(_, name, c)   => s"tool($name): $c"
