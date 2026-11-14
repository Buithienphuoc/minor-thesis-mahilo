package mahilo.bootstrap

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Đọc file `.env` dạng `KEY=value` (dòng `#` là chú thích, giá trị có thể đặt trong nháy).
  * Biến môi trường thật luôn được ưu tiên hơn (xem `ServerMain.env`).
  * Không có file thì trả về map rỗng.
  */
object DotEnv:
  def load(path: String): Map[String, String] =
    val p = Path.of(path)
    if !Files.isRegularFile(p) then Map.empty
    else parse(Files.readAllLines(p, StandardCharsets.UTF_8).asScala.toList)

  def parse(lines: List[String]): Map[String, String] =
    lines.iterator
      .map(_.trim)
      .filter(l => l.nonEmpty && !l.startsWith("#"))
      .map(_.stripPrefix("export ").trim)
      .flatMap { l =>
        l.split("=", 2) match
          case Array(k, v) =>
            val value = v.trim match
              case q if q.length >= 2 && (q.head == '"' || q.head == '\'') && q.last == q.head => q.substring(1, q.length - 1)
              case other => other.split(" #", 2).head.trim // chú thích cuối dòng
            Some(k.trim -> value)
          case _ => None
      }
      .toMap
