package mahilo.domain

/** Các kiểu định danh dùng khắp hệ thống.
  *
  * Python dùng `str` cho mọi thứ (tên agent, id tin nhắn...), nên rất dễ truyền nhầm
  * chỗ. Ở đây mỗi loại là một `opaque type`: lúc chạy vẫn chỉ là `String` (không tốn
  * thêm bộ nhớ), nhưng compiler không cho trộn `AgentName` với `MessageId`.
  */
opaque type AgentName = String
object AgentName:
  def apply(value: String): AgentName = value

  /** Tên "agent hệ thống" dùng làm người gửi cho tin lỗi (Python: sender="mahilo"). */
  val System: AgentName = "mahilo"

  extension (name: AgentName) def value: String = name

opaque type MessageId = String
object MessageId:
  def apply(value: String): MessageId = value
  extension (id: MessageId) def value: String = id
