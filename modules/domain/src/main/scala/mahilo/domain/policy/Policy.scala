package mahilo.domain.policy


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
