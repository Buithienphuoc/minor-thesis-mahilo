package mahilo.domain.tool

/** Hai tool nền tảng mà agent nào cũng có (Python: `_get_base_tools()`). */
object BuiltinTools:
  val ChatWithAgent = "chat_with_agent"
  val ContactHuman  = "contact_human"

  /** Khác Python: bỏ tham số `your_name`. Người gửi được lấy từ agent đang chạy, LLM
    * không còn tự khai tên mình (và không thể mạo danh agent khác).
    */
  def chatWithAgent(contactable: Map[String, String]): ToolSpec =
    val list =
      if contactable.isEmpty then "(không có)"
      else contactable.map((n, d) => s"$n: $d").mkString("; ")
    ToolSpec(
      name = ChatWithAgent,
      description =
        "Chat with an agent by their name. You are already given the list of agent names you can talk to. " +
          "Determine what agent would be best suited to answer a question and also what question should be asked. " +
          s"The agent names available to you are: $list. " +
          "Don't use any other agent names. You also shouldn't send a chat message to yourself.",
      params = List(
        ToolParam("agent_name", "The name of the agent to ask the question to."),
        ToolParam("question", "The question to ask the agent.")
      )
    )

  val contactHuman: ToolSpec = ToolSpec(
    name = ContactHuman,
    description =
      "Contact your human. Use this function whenever you want to send some information to your human " +
        "or get new information from your human.",
    params = List(ToolParam("message", "The message to send to the human."))
  )
