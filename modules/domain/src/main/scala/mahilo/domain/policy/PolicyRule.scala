package mahilo.domain.policy

import mahilo.domain.messaging.Envelope

/** Cách một policy quyết định. Python: `PolicyType` + `policy_content: Callable | str`. */
enum PolicyRule:
  /** Hàm thuần. Trả `Some(lý do)` nếu vi phạm, `None` nếu hợp lệ. */
  case Heuristic(check: (Envelope, PolicyContext) => Option[String])

  /** Quy tắc viết bằng lời; một LLM sẽ chấm (xem [[NaturalLanguageJudge]]). */
  case NaturalLanguage(text: String)
