package mahilo.domain.policy

import java.time.Instant
import mahilo.domain.messaging.Envelope

/** Ngữ cảnh để chấm một tin. Python: dict `{"timestamp": ..., "conversation_history": [...]}`.
  *
  * @param history các tin gần nhất giữa người gửi và người nhận, theo thứ tự THỜI GIAN
  *                TĂNG DẦN (cũ → mới). Python lấy `ORDER BY timestamp DESC` rồi lại coi
  *                `history[-1]` là tin mới nhất, nên anti_loop so sánh nhầm với tin cũ nhất.
  *                Ở đây thứ tự được ghi rõ trong kiểu và port phải tuân theo.
  */
final case class PolicyContext(history: List[Envelope], now: Instant)

/** Cách một policy quyết định. Python: `PolicyType` + `policy_content: Callable | str`. */
enum PolicyRule:
  /** Hàm thuần. Trả `Some(lý do)` nếu vi phạm, `None` nếu hợp lệ. */
  case Heuristic(check: (Envelope, PolicyContext) => Option[String])

  /** Quy tắc viết bằng lời; một LLM sẽ chấm (xem [[NaturalLanguageJudge]]). */
  case NaturalLanguage(text: String)

final case class Policy(
    name: String,
    description: String,
    rule: PolicyRule,
    priority: Int = 0,
    enabled: Boolean = false
):
  /** Python: policy có `priority >= 100` mà vi phạm thì dừng, không chấm tiếp. */
  def stopsEvaluationOnViolation: Boolean = priority >= Policy.StopPriority

object Policy:
  val StopPriority = 100

/** Python: `@dataclass PolicyViolation`. Thời điểm do nơi tạo truyền vào (Python dùng
  * `timestamp: float = time.time()` làm default, chỉ tính MỘT lần lúc import module).
  */
final case class PolicyViolation(policyName: String, reason: String, at: Instant)

/** Tập policy, luôn giữ thứ tự ưu tiên giảm dần. Python: `PolicyManager.policies`. */
final case class PolicySet(all: List[Policy]):
  def add(p: Policy): PolicySet          = PolicySet((all.filterNot(_.name == p.name) :+ p).sortBy(-_.priority))
  def remove(name: String): PolicySet    = PolicySet(all.filterNot(_.name == name))
  def get(name: String): Option[Policy]  = all.find(_.name == name)
  def enabled: List[Policy]              = all.filter(_.enabled)
  def setEnabled(name: String, on: Boolean): PolicySet =
    PolicySet(all.map(p => if p.name == name then p.copy(enabled = on) else p))

object PolicySet:
  def apply(policies: Policy*): PolicySet = PolicySet(policies.toList.sortBy(-_.priority))
