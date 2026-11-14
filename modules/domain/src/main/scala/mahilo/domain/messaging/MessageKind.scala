package mahilo.domain.messaging


/** Python: `class MessageType(Enum)`. */
enum MessageKind:
  case Direct, Broadcast, Response, Error

  def wire: String = this.toString.toLowerCase

object MessageKind:
  def fromWire(s: String): Option[MessageKind] = values.find(_.wire == s.toLowerCase)
