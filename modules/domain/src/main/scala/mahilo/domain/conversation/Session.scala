package mahilo.domain.conversation

import mahilo.domain.AgentName

/** Lịch sử hội thoại của MỘT agent với người dùng của nó. Python: `class Session`.
  *
  * Khác Python:
  *  - bất biến: thêm tin trả về `Session` mới, không sửa list dùng chung;
  *  - không tự ghi file. Lưu ở đâu là việc của port `SessionRepository`;
  *  - định danh theo `name` của agent (Python đặt file theo `TYPE`, hai agent cùng
  *    type sẽ ghi đè lên nhau).
  */
final case class Session(agent: AgentName, messages: Vector[ChatMessage] = Vector.empty):
  def append(more: Iterable[ChatMessage]): Session = copy(messages = messages ++ more)
  def :+(message: ChatMessage): Session          = copy(messages = messages :+ message)

  /** Python: `get_last_n_messages(n)`. Nếu lấy ra số lẻ (>1) thì lấy thêm 1 tin để đủ cặp. */
  def lastN(n: Int): Vector[ChatMessage] =
    val tail = messages.takeRight(n)
    if tail.size > 1 && tail.size % 2 != 0 then messages.takeRight(n + 1) else tail

object Session:
  def empty(agent: AgentName): Session = Session(agent)
