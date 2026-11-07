package mahilo.domain.agent

import mahilo.domain.AgentName

/** Agent được phép liên lạc với ai.
  *
  * Python: `can_contact: List[str] = []`, và `populate_can_contact_for_agents()` sẽ
  * thay list rỗng bằng "tất cả agent". Danh sách rỗng mang hai nghĩa ngược nhau, nên ở
  * đây tách thành hai trường hợp rõ ràng.
  */
enum ContactRule:
  case Everyone
  case Only(names: Set[AgentName])

  def allows(name: AgentName): Boolean = this match
    case Everyone    => true
    case Only(names) => names.contains(name)

/** Khai báo một agent: chỉ là DỮ LIỆU, không có hành vi.
  *
  * Python có `class BaseAgent` vừa giữ cấu hình, vừa giữ session, vừa gọi LLM, vừa nói
  * chuyện WebSocket. Các template (Dispatcher, Plumber...) là subclass chỉ để truyền
  * prompt. Ở bản Scala, template chỉ là một giá trị `AgentSpec`; hành vi nằm ở tầng
  * application (`AgentRuntime`).
  *
  * @param name             tên duy nhất, cũng là địa chỉ để agent khác gửi tin tới
  * @param kind             "loại" agent (Python: `TYPE`), chỉ dùng trong prompt
  * @param description      mô tả dài: chính là phần vai trò trong system prompt
  * @param shortDescription mô tả ngắn: hiện cho agent khác biết agent này làm gì
  * @param canContact       ai được nhắn tới
  */
final case class AgentSpec(
    name: AgentName,
    kind: String,
    description: String,
    shortDescription: String = "",
    canContact: ContactRule = ContactRule.Everyone
):
  /** Python: `get_contactable_agents_with_description()`. */
  def contactable(all: List[AgentSpec]): List[AgentSpec] =
    all.filter(other => other.name != name && canContact.allows(other.name))
