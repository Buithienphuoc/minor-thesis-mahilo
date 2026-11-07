"""Hợp đồng REST v1 giữa Mahilo-Scala và engine. Xem docs/remote-engine-contract.md."""

from typing import Any, Literal

from pydantic import BaseModel, Field


class WireToolCall(BaseModel):
    id: str
    name: str
    arguments: str = "{}"  # chuỗi JSON, giữ nguyên như model đã viết


class WireMessage(BaseModel):
    """Một tin theo định dạng OpenAI Chat Completions."""

    role: Literal["system", "user", "assistant", "tool"]
    content: str | None = None
    tool_calls: list[WireToolCall] = Field(default_factory=list)
    tool_call_id: str | None = None
    name: str | None = None


class WireTool(BaseModel):
    name: str
    description: str = ""
    parameters: dict[str, Any] = Field(default_factory=lambda: {"type": "object", "properties": {}})


class AgentRef(BaseModel):
    name: str
    kind: str = ""


class Callback(BaseModel):
    url: str  # tool chạy tại POST {url}/{tool_name}


class TurnRequest(BaseModel):
    turn_id: str
    agent: AgentRef
    messages: list[WireMessage]
    tools: list[WireTool] = Field(default_factory=list)
    max_iterations: int = 10
    callback: Callback


class ToolCallRecord(BaseModel):
    id: str
    name: str
    arguments: str
    result: str


class TurnResponse(BaseModel):
    reply: str | None
    tool_calls: list[ToolCallRecord] = Field(default_factory=list)
    stopped_early: bool = False
