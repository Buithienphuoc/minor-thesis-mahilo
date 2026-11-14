package mahilo.domain.messaging

/** Một tin đã lưu: phong bì + trạng thái. */
final case class StoredMessage(envelope: Envelope, state: DeliveryState)
