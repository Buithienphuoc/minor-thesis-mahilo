package mahilo.application.service

import cats.effect.{IO, Ref}
import cats.effect.std.Console
import cats.syntax.all.*
import mahilo.application.port.out.{LlmClient, LlmRequest}
import mahilo.domain.conversation.ChatMessage
import mahilo.domain.messaging.Envelope
import mahilo.domain.policy.*

/** Chấm một tin theo các policy đang bật. Python: `PolicyManager` + `MessageValidator`.
  *
  * Luật chấm (thuần) nằm ở domain; class này chỉ lo phần có hiệu ứng: giữ tập policy
  * có thể thay đổi lúc chạy, gọi LLM cho policy viết bằng lời, ghi lịch sử vi phạm.
  */
final class PolicyEngine private (
    policies: Ref[IO, PolicySet],
    history: Ref[IO, Vector[PolicyViolation]],
    judge: LlmClient,
    judgeModel: Option[String]
):
  def current: IO[PolicySet]                      = policies.get
  def update(f: PolicySet => PolicySet): IO[Unit] = policies.update(f)

  def violations(limit: Int, policyName: Option[String]): IO[List[PolicyViolation]] =
    history.get.map(_.filter(v => policyName.forall(_ == v.policyName)).reverse.take(limit).toList)

  /** Duyệt policy theo priority giảm dần; gặp vi phạm ở policy priority ≥ 100 thì dừng. */
  def evaluate(message: Envelope, ctx: PolicyContext): IO[List[PolicyViolation]] =
    def loop(remaining: List[Policy], found: List[PolicyViolation]): IO[List[PolicyViolation]] =
      remaining match
        case Nil => IO.pure(found.reverse)
        case p :: rest =>
          check(p, message, ctx).flatMap {
            case None => loop(rest, found)
            case Some(reason) =>
              val v = PolicyViolation(p.name, reason, ctx.now)
              history.update(_ :+ v) *>
                (if p.stopsEvaluationOnViolation then IO.pure((v :: found).reverse) else loop(rest, v :: found))
          }
    policies.get.flatMap(set => loop(set.enabled, Nil))

  /** Lỗi khi chấm (vd LLM sập) được coi là "qua", giống Python. */
  private def check(p: Policy, message: Envelope, ctx: PolicyContext): IO[Option[String]] =
    val verdict = p.rule match
      case PolicyRule.Heuristic(f) => IO(f(message, ctx))
      case PolicyRule.NaturalLanguage(text) =>
        val prompt = NaturalLanguageJudge.prompt(text, message, ctx)
        judge
          .complete(LlmRequest(List(ChatMessage.User(prompt)), model = judgeModel))
          .map(r => NaturalLanguageJudge.parse(r.content.getOrElse("")))
    verdict.handleErrorWith(e => Console[IO].errorln(s"[policy] lỗi khi chấm '${p.name}': ${e.getMessage}").as(None))

object PolicyEngine:
  def make(initial: PolicySet, judge: LlmClient, judgeModel: Option[String] = None): IO[PolicyEngine] =
    (Ref.of[IO, PolicySet](initial), Ref.of[IO, Vector[PolicyViolation]](Vector.empty))
      .mapN(PolicyEngine(_, _, judge, judgeModel))
