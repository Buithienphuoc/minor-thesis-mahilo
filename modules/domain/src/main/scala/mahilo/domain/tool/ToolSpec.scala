package mahilo.domain.tool

/** Mô tả một tool để đưa cho LLM. Python: dict `{"type": "function", "function": {...}}`.
  *
  * Domain chỉ giữ mô tả. Việc chuyển thành JSON schema theo định dạng của nhà cung cấp
  * LLM nằm ở adapter LLM.
  */
final case class ToolSpec(name: String, description: String, params: List[ToolParam]):
  def missingRequired(args: Map[String, String]): List[String] =
    params.filter(p => p.required && !args.contains(p.name)).map(_.name)
