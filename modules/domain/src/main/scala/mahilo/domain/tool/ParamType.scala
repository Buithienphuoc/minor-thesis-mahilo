package mahilo.domain.tool

/** Kiểu của một tham số tool (đủ dùng cho các ví dụ của Mahilo). */
enum ParamType:
  case StringType, NumberType, BooleanType

  def jsonSchemaName: String = this match
    case StringType  => "string"
    case NumberType  => "number"
    case BooleanType => "boolean"
