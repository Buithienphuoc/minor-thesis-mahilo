"""Chuyển lịch sử định dạng OpenAI (của Mahilo) sang message của PydanticAI.

Mahilo gửi: [lịch sử session..., system prompt, tin user mới]. PydanticAI muốn:
``instructions`` (system), ``message_history`` (lịch sử) và ``user_prompt`` (tin mới).
"""

from dataclasses import dataclass

from pydantic_ai.messages import (
    ModelMessage,
    ModelRequest,
    ModelResponse,
    TextPart,
    ToolCallPart,
    ToolReturnPart,
    UserPromptPart,
)

from .contract import WireMessage


@dataclass
class SplitHistory:
    instructions: str
    history: list[ModelMessage]
    user_prompt: str


def split(messages: list[WireMessage]) -> SplitHistory:
    instructions = "\n\n".join(m.content or "" for m in messages if m.role == "system")
    rest = [m for m in messages if m.role != "system"]
    if not rest or rest[-1].role != "user":
        raise ValueError("Tin cuối cùng của lượt phải là tin 'user'")
    *past, last = rest
    return SplitHistory(instructions, [_to_model_message(m) for m in past], last.content or "")


def _to_model_message(m: WireMessage) -> ModelMessage:
    match m.role:
        case "user":
            return ModelRequest(parts=[UserPromptPart(content=m.content or "")])
        case "tool":
            return ModelRequest(
                parts=[ToolReturnPart(tool_name=m.name or "", content=m.content or "", tool_call_id=m.tool_call_id or "")]
            )
        case "assistant":
            parts = []
            if m.content:
                parts.append(TextPart(content=m.content))
            parts += [ToolCallPart(tool_name=c.name, args=c.arguments, tool_call_id=c.id) for c in m.tool_calls]
            return ModelResponse(parts=parts or [TextPart(content="")])
        case _:
            raise ValueError(f"role không hỗ trợ trong lịch sử: {m.role}")
