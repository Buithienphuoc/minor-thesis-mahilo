"""LÕI của engine: chạy MỘT lượt của agent Mahilo bằng PydanticAI.

Tương đương ``mahilo/integrations/pydanticai/agent.py`` (``PydanticAIAgent``) bên Mahilo
Python, nhưng tách khỏi Mahilo: engine không tự gửi tin cho agent khác, không tự nói với
người dùng. Nó chỉ gọi ``ToolCaller`` và Mahilo-Scala làm phần còn lại.
"""

import json
from typing import Any, Protocol

from pydantic_ai import Agent, RunContext, Tool
from pydantic_ai.exceptions import UsageLimitExceeded
from pydantic_ai.messages import ModelResponse, ToolCallPart, ToolReturnPart
from pydantic_ai.models import Model
from pydantic_ai.usage import UsageLimits

from pydantic import BaseModel

from . import history, profiles
from .contract import ToolCallRecord, TurnRequest, TurnResponse, WireTool


class ToolCaller(Protocol):
    """PORT RA: chạy một tool ở phía Mahilo-Scala và trả kết quả dạng chuỗi."""

    async def call(self, tool_name: str, tool_call_id: str, arguments: dict[str, Any]) -> str: ...


def _make_tool(spec: WireTool, caller: ToolCaller) -> Tool:
    """Tạo một tool PydanticAI từ JSON schema Mahilo gửi sang. Thân tool chỉ chuyển tiếp."""

    async def forward(ctx: RunContext[None], **kwargs: Any) -> str:
        return await caller.call(spec.name, ctx.tool_call_id or "", kwargs)

    return Tool.from_schema(
        forward, name=spec.name, description=spec.description, json_schema=spec.parameters, takes_ctx=True
    )


async def run_turn(req: TurnRequest, model: Model | str, caller: ToolCaller) -> TurnResponse:
    split = history.split(req.messages)
    profile = profiles.for_agent(req.agent.name)
    agent = Agent(
        model,
        instructions=split.instructions + "\n" + profile.extra_instructions,
        tools=[_make_tool(t, caller) for t in req.tools],
        output_type=profile.output_type,
        name=req.agent.name,
    )
    try:
        result = await agent.run(
            split.user_prompt,
            message_history=split.history,
            usage_limits=UsageLimits(request_limit=req.max_iterations),
        )
    except UsageLimitExceeded:
        return TurnResponse(reply="(Đã dừng: quá số vòng gọi model cho phép)", stopped_early=True)

    return TurnResponse(
        reply=_render(result.output),
        # Chỉ ghi lại tool của Mahilo; tool nội bộ của PydanticAI (vd "final_result" cho
        # output có cấu trúc) không phải việc của Mahilo.
        tool_calls=[t for t in _tool_trace(result.new_messages()) if t.name in {x.name for x in req.tools}],
    )


def _render(output: Any) -> str | None:
    if output is None:
        return None
    if isinstance(output, BaseModel):
        return output.model_dump_json()
    return str(output)


def _tool_trace(messages) -> list[ToolCallRecord]:
    """Ghép mỗi ToolCallPart với ToolReturnPart tương ứng (theo tool_call_id)."""
    returns = {
        p.tool_call_id: p
        for m in messages
        for p in getattr(m, "parts", [])
        if isinstance(p, ToolReturnPart)
    }
    out: list[ToolCallRecord] = []
    for m in messages:
        if not isinstance(m, ModelResponse):
            continue
        for p in m.parts:
            if isinstance(p, ToolCallPart):
                args = p.args if isinstance(p.args, str) else json.dumps(p.args or {}, ensure_ascii=False)
                ret = returns.get(p.tool_call_id)
                out.append(ToolCallRecord(id=p.tool_call_id, name=p.tool_name, arguments=args,
                                          result=str(ret.content) if ret else ""))
    return out
