package mahilo.application.port.in

import cats.effect.IO
import mahilo.domain.AgentName

// =====================================================================
//  PORT VÀO (driving ports): những gì thế giới bên ngoài được phép yêu cầu lõi làm.
//  Adapter HTTP/WebSocket, CLI, test... chỉ nói chuyện với lõi qua các trait này.
// =====================================================================

/** Người dùng nhắn cho agent của mình. Python: `BaseAgent.process_chat_message`. */
trait ChatUseCase:
  def chat(agent: AgentName, text: String): IO[ChatOutcome]
