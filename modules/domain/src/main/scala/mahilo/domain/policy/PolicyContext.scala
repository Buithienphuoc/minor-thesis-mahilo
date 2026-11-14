package mahilo.domain.policy

import java.time.Instant
import mahilo.domain.messaging.Envelope

/** Ngữ cảnh để chấm một tin. Python: dict `{"timestamp": ..., "conversation_history": [...]}`.
  *
  * @param history các tin gần nhất giữa người gửi và người nhận, theo thứ tự THỜI GIAN
  *                TĂNG DẦN (cũ → mới). Python lấy `ORDER BY timestamp DESC` rồi lại coi
  *                `history[-1]` là tin mới nhất, nên anti_loop so sánh nhầm với tin cũ nhất.
  *                Ở đây thứ tự được ghi rõ trong kiểu và port phải tuân theo.
  */
final case class PolicyContext(history: List[Envelope], now: Instant)
