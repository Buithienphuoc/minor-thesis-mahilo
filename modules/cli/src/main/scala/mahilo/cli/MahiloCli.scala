package mahilo.cli

import java.net.URI
import java.net.http.{HttpClient, WebSocket}
import java.util.concurrent.{CompletionStage, CountDownLatch}
import scala.io.StdIn

/** Client dòng lệnh, tương đương `mahilo connect` của bản Python (`mahilo/cli.py`, `client.py`).
  *
  *   sbt "cli/run --agent-name SalesAgent"
  *   sbt "cli/run --agent-name dispatcher --url http://localhost:8000"
  *
  * Mỗi terminal = một người dùng đứng sau một agent. Gõ tin rồi Enter; mọi tin agent đẩy
  * về (câu trả lời trực tiếp, hoặc `contact_human` khi agent khác nhắn tới) đều in ra.
  * Gõ `quit` để thoát.
  *
  * Đây là chương trình riêng, KHÔNG phụ thuộc lõi Mahilo: nó chỉ nói chuyện qua
  * WebSocket `/ws/{agent}`, giống bất kỳ client nào khác (web, mobile...).
  *
  * Khác Python: gửi văn bản thô. Client Python gửi `json.dumps(text)`, nên server nhận
  * chuỗi có dấu ngoặc kép và ký tự Unicode bị mã hoá `à`. Thêm `--json` để giữ
  * đúng hành vi cũ nếu cần so sánh.
  */
object MahiloCli:

  final case class Options(agent: String, url: String = "http://localhost:8000", json: Boolean = false)

  def parse(args: List[String]): Either[String, Options] =
    def loop(rest: List[String], agent: Option[String], url: String, json: Boolean): Either[String, Options] = rest match
      case Nil                                 => agent.toRight(usage).map(Options(_, url, json))
      case "connect" :: tail                   => loop(tail, agent, url, json) // cho giống `mahilo connect`
      case ("--agent-name" | "-a") :: v :: tail => loop(tail, Some(v), url, json)
      case "--url" :: v :: tail                 => loop(tail, agent, v, json)
      case "--json" :: tail                     => loop(tail, agent, url, true)
      case other :: _                           => Left(s"Tham số không hợp lệ: $other\n$usage")
    loop(args, None, "http://localhost:8000", false)

  val usage = "Cách dùng: cli/run [connect] --agent-name <agent> [--url http://localhost:8000] [--json]"

  /** "http://host:8000" → "ws://host:8000/ws/agent". */
  def wsUri(o: Options): URI =
    val hostPort = o.url.split("://").last.stripSuffix("/")
    val scheme   = if o.url.startsWith("https") then "wss" else "ws"
    URI.create(s"$scheme://$hostPort/ws/${o.agent}")

  /** Nhận tin; WebSocket có thể chia một tin thành nhiều mảnh (`last = false`). */
  final class Printer(agent: String, onMessage: String => Unit, closed: CountDownLatch) extends WebSocket.Listener:
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

  def connect(o: Options, onMessage: String => Unit, closed: CountDownLatch): WebSocket =
    HttpClient.newHttpClient().newWebSocketBuilder().buildAsync(wsUri(o), Printer(o.agent, onMessage, closed)).join()

  def encode(o: Options, text: String): String =
    if !o.json then text
    else "\"" + text.flatMap {
      case '"'            => "\\\""
      case '\\'           => "\\\\"
      case c if c > 127   => f"\\u${c.toInt}%04x"
      case c              => c.toString
    } + "\""

  def main(args: Array[String]): Unit =
    parse(args.toList) match
      case Left(err) =>
        System.err.println(err)
        sys.exit(2)
      case Right(o) =>
        val closed = CountDownLatch(1)
        println(s"Connecting to ${wsUri(o)}")
        val ws =
          try connect(o, msg => println(s"\n${o.agent}: $msg\n"), closed)
          catch
            case e: java.util.concurrent.CompletionException =>
              System.err.println(s"Không nối được tới ${wsUri(o)} (${e.getCause}). Server Mahilo-Scala đã chạy chưa? Agent có đúng tên không?")
              sys.exit(1)
        // Vòng nhập liệu, giống input(...) của client Python.
        var running = true
        while running && closed.getCount > 0 do
          val line = StdIn.readLine(s"Enter message for ${o.agent} (or 'quit' to exit): ")
          if line == null || line.trim.equalsIgnoreCase("quit") then running = false
          else if line.trim.nonEmpty then ws.sendText(encode(o, line), true).join()
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").exceptionally(_ => null).join()
