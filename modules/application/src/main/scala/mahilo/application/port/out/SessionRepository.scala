package mahilo.application.port.out

import cats.effect.IO
import mahilo.domain.AgentName
import mahilo.domain.conversation.Session

/** PORT RA: lưu lịch sử hội thoại từng agent. Python: `Session` tự đọc/ghi file JSONL. */
trait SessionRepository:
  /** Chưa có thì trả session rỗng. */
  def load(agent: AgentName): IO[Session]
  def save(session: Session): IO[Unit]
