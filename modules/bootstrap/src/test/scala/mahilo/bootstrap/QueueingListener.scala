package mahilo.bootstrap

import java.net.http.WebSocket
import java.util.concurrent.{CompletionStage, LinkedBlockingQueue}

/** Listener WebSocket cho test: bỏ mỗi frame text nhận được vào `inbox`. */
final class QueueingListener(inbox: LinkedBlockingQueue[String]) extends WebSocket.Listener:
  override def onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage[?] =
    inbox.put(data.toString)
    ws.request(1)
    null
