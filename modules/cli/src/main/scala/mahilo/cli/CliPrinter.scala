package mahilo.cli

import java.net.http.WebSocket
import java.util.concurrent.{CompletionStage, CountDownLatch}

/** Nhận tin; WebSocket có thể chia một tin thành nhiều mảnh (`last = false`). */
final class CliPrinter(agent: String, onMessage: String => Unit, closed: CountDownLatch) extends WebSocket.Listener:
  private val buffer = StringBuilder()
  override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
    buffer.append(data)
    if last then
      onMessage(buffer.toString)
      buffer.clear()
    ws.request(1)
    null
  override def onClose(ws: WebSocket, code: Int, reason: String): CompletionStage[?] =
    println(s"\n⚠️  Kết nối tới $agent đã đóng ($code $reason)")
    closed.countDown()
    null
  override def onError(ws: WebSocket, error: Throwable): Unit =
    println(s"\n⚠️  Lỗi kết nối: ${error.getMessage}")
    closed.countDown()
