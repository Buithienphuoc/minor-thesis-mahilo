package mahilo.adapters.http

import io.circe.Json
import io.circe.syntax.*
import mahilo.application.port.in.AgentStatus
import mahilo.application.port.out.MetricsSnapshot
import mahilo.domain.messaging.{Envelope, StoredMessage}
import mahilo.domain.policy.{Policy, PolicyRule, PolicyViolation}

/** Cách hiển thị kiểu domain thành JSON cho API. Chỉ adapter HTTP cần, nên nằm ở đây. */
object JsonViews:

  def envelope(e: Envelope): Json = Json.obj(
    "message_id"     -> e.id.value.asJson,
    "sender"         -> e.sender.value.asJson,
    "recipient"      -> e.recipient.value.asJson,
    "message_type"   -> e.kind.wire.asJson,
    "payload"        -> e.payload.asJson,
    "timestamp"      -> (e.timestamp.toEpochMilli / 1000.0).asJson,
    "correlation_id" -> e.correlationId.asJson,
    "reply_to"       -> e.replyTo.map(_.value).asJson
  )

  def stored(m: StoredMessage): Json =
    envelope(m.envelope).deepMerge(Json.obj("state" -> m.state.label.asJson, "retry_count" -> m.state.retryCount.asJson))

  def agent(a: AgentStatus): Json = Json.obj(
    "name"              -> a.name.value.asJson,
    "kind"              -> a.kind.asJson,
    "short_description" -> a.shortDescription.asJson,
    "active"            -> a.active.asJson
  )

  def metrics(m: MetricsSnapshot): Json = Json.obj(
    "counters"               -> m.counters.asJson,
    "llm_latency_avg_millis" -> m.llmLatencyAvgMillis.asJson
  )

  def policy(p: Policy): Json = Json.obj(
    "name"        -> p.name.asJson,
    "description" -> p.description.asJson,
    "type"        -> (p.rule match { case _: PolicyRule.Heuristic => "heuristic"; case _ => "natural_language" }).asJson,
    "priority"    -> p.priority.asJson,
    "enabled"     -> p.enabled.asJson
  )

  def violation(v: PolicyViolation): Json = Json.obj(
    "policy_name" -> v.policyName.asJson,
    "reason"      -> v.reason.asJson,
    "timestamp"   -> v.at.toString.asJson
  )
