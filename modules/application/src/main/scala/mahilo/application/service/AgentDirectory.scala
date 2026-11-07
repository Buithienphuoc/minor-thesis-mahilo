package mahilo.application.service

import cats.effect.{IO, Ref}
import mahilo.domain.AgentName
import mahilo.domain.agent.AgentSpec

/** Danh bạ agent + trạng thái "active". Python: phần registry của `AgentManager`, cộng
  * `GlobalRegistry` (singleton toàn cục). Ở đây không có singleton: ai cần thì được
  * truyền vào qua constructor.
  *
  * "Active" = agent đã được bật và sẽ nhận tin từ hàng đợi. Python biểu diễn bằng
  * `_session is not None`, trộn hai khái niệm khác nhau.
  */
final class AgentDirectory private (
    definitions: List[AgentDefinition],
    active: Ref[IO, Set[AgentName]]
):
  private val byName: Map[AgentName, AgentDefinition] = definitions.map(d => d.spec.name -> d).toMap

  def get(name: AgentName): Option[AgentDefinition] = byName.get(name)
  def all: List[AgentDefinition]                    = definitions
  def specs: List[AgentSpec]                        = definitions.map(_.spec)

  def contactableFor(spec: AgentSpec): List[AgentSpec] = spec.contactable(specs)

  def isActive(name: AgentName): IO[Boolean] = active.get.map(_.contains(name))
  def activeNames: IO[Set[AgentName]]        = active.get

  /** @return true nếu lần này mới bật (trước đó chưa active) */
  def activate(name: AgentName): IO[Boolean] =
    active.modify(set => (set + name, !set.contains(name)))

object AgentDirectory:
  def make(definitions: List[AgentDefinition]): IO[AgentDirectory] =
    val dupes = definitions.groupBy(_.spec.name).collect { case (n, ds) if ds.size > 1 => n.value }
    if dupes.nonEmpty then IO.raiseError(IllegalArgumentException(s"Trùng tên agent: ${dupes.mkString(", ")}"))
    else Ref.of[IO, Set[AgentName]](Set.empty).map(AgentDirectory(definitions, _))
