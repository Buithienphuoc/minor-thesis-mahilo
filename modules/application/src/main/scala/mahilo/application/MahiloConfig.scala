package mahilo.application

import scala.concurrent.duration.*
import cats.syntax.all.*
import mahilo.domain.messaging.RetryPolicy
import mahilo.domain.policy.{PolicySet, StandardPolicies}

final case class MahiloConfig(
    contextWindow: Int = 7,
    maxToolIterations: Int = 10,
    retry: RetryPolicy = RetryPolicy(maxRetries = 3),
    retryDelay: FiniteDuration = 1.second,
    policyHistorySize: Int = 10,
    policies: PolicySet = StandardPolicies.all, // 4 policy mặc định, đều tắt
    policyJudgeModel: Option[String] = None
)
