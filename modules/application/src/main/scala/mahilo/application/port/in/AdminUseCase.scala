package mahilo.application.port.in

import cats.effect.IO
import mahilo.domain.AgentName
import mahilo.domain.policy.Policy

/** Quản trị lúc chạy. */
trait AdminUseCase:
  /** Python: `agent.activate()`. Trả false nếu agent không tồn tại. */
  def activate(agent: AgentName): IO[Boolean]
  def enablePolicy(name: String): IO[Unit]
  def disablePolicy(name: String): IO[Unit]
  def addPolicy(policy: Policy): IO[Unit]
