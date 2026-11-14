package mahilo.domain.tool

final case class ToolParam(
    name: String,
    description: String,
    tpe: ParamType = ParamType.StringType,
    required: Boolean = true
)
