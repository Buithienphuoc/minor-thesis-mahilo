package mahilo.application.port.out

import mahilo.domain.messaging.Envelope

/** PORT RA: ký và kiểm tra chữ ký phong bì. Python: JWT HS256 trong `MessageEnvelope`.
  *
  * Ký là phép tính thuần (không IO) nên trait trả giá trị trực tiếp.
  */
trait EnvelopeSigner:
  def sign(envelope: Envelope): Envelope
  def verify(envelope: Envelope): Boolean

object EnvelopeSigner:
  /** Không ký, không kiểm tra (Python: khi `secret_key=None`). */
  val Disabled: EnvelopeSigner = new EnvelopeSigner:
    def sign(envelope: Envelope): Envelope   = envelope
    def verify(envelope: Envelope): Boolean  = true
