package mahilo.domain.policy

/** Độ giống nhau giữa hai chuỗi theo thuật toán Ratcliff/Obershelp, tức cái Python gọi
  * qua `difflib.SequenceMatcher(None, a, b).ratio()`.
  *
  * ratio = 2 * M / (len(a) + len(b)), với M là tổng độ dài các khối trùng tìm được bằng
  * cách: lấy khối chung dài nhất, rồi đệ quy với phần bên trái và bên phải của nó.
  * (Bỏ qua heuristic "autojunk" của difflib, vốn chỉ bật với chuỗi ≥ 200 ký tự.)
  */
object TextSimilarity:

  def ratio(a: String, b: String): Double =
    val total = a.length + b.length
    if total == 0 then 1.0 else 2.0 * matches(a, b) / total

  private def matches(a: String, b: String): Int =
    if a.isEmpty || b.isEmpty then 0
    else
      val (i, j, size) = longestCommonBlock(a, b)
      if size == 0 then 0
      else
        size +
          matches(a.substring(0, i), b.substring(0, j)) +
          matches(a.substring(i + size), b.substring(j + size))

  /** Trả (vị trí trong a, vị trí trong b, độ dài) của khối chung dài nhất, ưu tiên khối xuất hiện sớm. */
  private def longestCommonBlock(a: String, b: String): (Int, Int, Int) =
    var best = (0, 0, 0)
    // prev(j) = độ dài khối chung kết thúc tại a(i-1), b(j-1)
    var prev = Array.fill(b.length + 1)(0)
    for i <- 1 to a.length do
      val cur = Array.fill(b.length + 1)(0)
      for j <- 1 to b.length do
        if a(i - 1) == b(j - 1) then
          cur(j) = prev(j - 1) + 1
          if cur(j) > best._3 then best = (i - cur(j), j - cur(j), cur(j))
      prev = cur
    best
