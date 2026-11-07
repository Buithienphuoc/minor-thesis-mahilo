package mahilo.domain.telemetry

import mahilo.domain.AgentName
import mahilo.domain.messaging.Envelope
import mahilo.domain.policy.PolicyViolation

/** Các sự kiện hệ thống đáng ghi lại. Python: `class EventType(Enum)` + dict `details`. */
enum TelemetryEvent:
  case AgentRegistered(agent: AgentName)
  case AgentActivated(agent: AgentName)
  case MessageSent(envelope: Envelope)
  case MessageRejected(envelope: Envelope, violations: List[PolicyViolation])
  case MessageProcessed(envelope: Envelope)
  case MessageRetried(envelope: Envelope, attempt: Int)
  case MessageFailed(envelope: Envelope, attempt: Int)
  case HumanContacted(agent: AgentName, deliveredTo: Int)
  case LlmCalled(agent: AgentName, millis: Long)
  case ToolCalled(agent: AgentName, tool: String, succeeded: Boolean)

  /** Agent mà sự kiện "thuộc về", để lọc metric theo agent. */
  def subject: AgentName = this match
    case AgentRegistered(a)     => a
    case AgentActivated(a)      => a
    case MessageSent(e)         => e.sender
    case MessageRejected(e, _)  => e.sender
    case MessageProcessed(e)    => e.recipient
    case MessageRetried(e, _)   => e.recipient
    case MessageFailed(e, _)    => e.recipient
    case HumanContacted(a, _)   => a
    case LlmCalled(a, _)        => a
    case ToolCalled(a, _, _)    => a

  def name: String = this.productPrefix
