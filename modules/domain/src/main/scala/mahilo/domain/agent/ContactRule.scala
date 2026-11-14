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
