package mahilo.domain.policy


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
