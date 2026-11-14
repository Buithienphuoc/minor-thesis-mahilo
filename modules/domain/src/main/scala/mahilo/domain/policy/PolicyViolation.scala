package mahilo.domain.policy

import java.time.Instant

/** Python: `@dataclass PolicyViolation`. Thời điểm do nơi tạo truyền vào (Python dùng
  * `timestamp: float = time.time()` làm default, chỉ tính MỘT lần lúc import module).
  */
final case class PolicyViolation(policyName: String, reason: String, at: Instant)
