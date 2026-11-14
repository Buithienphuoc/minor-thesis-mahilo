package mahilo.application.service

import cats.syntax.all.*
import mahilo.domain.messaging.*
import mahilo.domain.policy.PolicyViolation

/** Kết quả gửi một tin. Python luôn trả "Message sent" kể cả khi policy chặn. */
enum SendOutcome:
  case Queued(envelope: Envelope)
  case Rejected(envelope: Envelope, violations: List[PolicyViolation])
