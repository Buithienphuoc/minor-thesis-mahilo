package mahilo.adapters.local

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import mahilo.application.port.out.EnvelopeSigner
import mahilo.domain.messaging.Envelope

/** ADAPTER cho port `EnvelopeSigner`: HMAC-SHA256 bằng JDK, không cần thư viện.
  * Python dùng JWT HS256, về bản chất cũng là HMAC-SHA256 trên `{message_id, payload}`.
  */
final class HmacEnvelopeSigner(secret: String) extends EnvelopeSigner:
  private val key = SecretKeySpec(secret.getBytes("UTF-8"), "HmacSHA256")

  private def mac(input: String): String =
    val m = Mac.getInstance("HmacSHA256")
    m.init(key)
    Base64.getUrlEncoder.withoutPadding.encodeToString(m.doFinal(input.getBytes("UTF-8")))

  def sign(envelope: Envelope): Envelope = envelope.copy(signature = Some(mac(envelope.signingInput)))

  def verify(envelope: Envelope): Boolean =
    envelope.signature.exists { sig =>
      // So sánh thời gian hằng để không lộ thông tin qua thời gian phản hồi.
      MessageDigest.isEqual(sig.getBytes("UTF-8"), mac(envelope.signingInput).getBytes("UTF-8"))
    }
