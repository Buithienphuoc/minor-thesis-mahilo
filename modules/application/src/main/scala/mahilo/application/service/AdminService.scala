package mahilo.application.service

import cats.effect.IO
import mahilo.application.port.in.AdminUseCase
import mahilo.domain.AgentName
import mahilo.domain.policy.Policy

/** Cài đặt port vào `AdminUseCase`: bật agent, bật/tắt/thêm policy. */
final class AdminService(runtime: AgentRuntime, policies: PolicyEngine) extends AdminUseCase:
  def activate(agent: AgentName): IO[Boolean] = runtime.activate(agent)
  def enablePolicy(name: String): IO[Unit]    = policies.update(_.setEnabled(name, true))
  def disablePolicy(name: String): IO[Unit]   = policies.update(_.setEnabled(name, false))
  def addPolicy(policy: Policy): IO[Unit]     = policies.update(_.add(policy))
