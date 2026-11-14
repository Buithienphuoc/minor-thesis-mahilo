package mahilo.application.service

import cats.effect.IO
import mahilo.domain.tool.ToolSpec

/** Tool do người dùng framework tự viết. Python: `tools=[{"tool": {...}, "function": f}]`.
  *
  * Mô tả (`spec`) là domain; cách chạy (`run`) có hiệu ứng nên nằm ở application.
  */
final case class CustomTool(spec: ToolSpec, run: Map[String, String] => IO[String])
