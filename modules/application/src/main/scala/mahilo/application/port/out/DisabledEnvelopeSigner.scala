package mahilo.application.port.out

import mahilo.domain.messaging.Envelope

/** Không ký, không kiểm tra (Python: khi `secret_key=None`). Dùng qua `EnvelopeSigner.Disabled`. */
object DisabledEnvelopeSigner extends EnvelopeSigner:
  def sign(envelope: Envelope): Envelope  = envelope
  def verify(envelope: Envelope): Boolean = true
