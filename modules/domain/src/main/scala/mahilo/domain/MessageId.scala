package mahilo.domain

opaque type MessageId = String
object MessageId:
  def apply(value: String): MessageId = value
  extension (id: MessageId) def value: String = id
