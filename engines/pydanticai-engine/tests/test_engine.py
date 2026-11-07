import asyncio

from fastapi.testclient import TestClient

from mahilo_pydanticai import app as app_module
from mahilo_pydanticai.contract import TurnRequest
from mahilo_pydanticai.demo_model import demo_model
from mahilo_pydanticai.engine import run_turn

TOOLS = [
    {"name": "chat_with_agent", "description": "Chat with an agent",
     "parameters": {"type": "object",
                    "properties": {"agent_name": {"type": "string"}, "question": {"type": "string"}},
                    "required": ["agent_name", "question"]}},
    {"name": "contact_human", "description": "Contact your human",
     "parameters": {"type": "object", "properties": {"message": {"type": "string"}}, "required": ["message"]}},
]


class FakeCaller:
    def __init__(self):
        self.calls = []

    async def call(self, tool_name, tool_call_id, arguments):
        self.calls.append((tool_name, arguments))
        return f"ok:{tool_name}"


def request(user_text: str, history=None) -> TurnRequest:
    return TurnRequest.model_validate({
        "turn_id": "t1",
        "agent": {"name": "plumber", "kind": "plumber"},
        "messages": (history or []) + [
            {"role": "system", "content": "You are plumber"},
            {"role": "user", "content": user_text},
        ],
        "tools": TOOLS,
        "callback": {"url": "http://unused"},
    })


def test_pending_message_goes_to_contact_human():
    caller = FakeCaller()
    res = asyncio.run(run_turn(request("Pending messages: dispatcher: toilet clogged"), demo_model(), caller))
    assert caller.calls == [("contact_human", {"message": "[PydanticAI] Điều phối viên hỏi: toilet clogged"})]
    assert res.reply == "Đã hỏi thợ, đang chờ trả lời."
    assert [t.name for t in res.tool_calls] == ["contact_human"]
    assert res.tool_calls[0].result == "ok:contact_human"


def test_direct_message_with_history_goes_to_dispatcher():
    history = [
        {"role": "user", "content": "Pending messages: dispatcher: hi"},
        {"role": "assistant", "content": None,
         "tool_calls": [{"id": "c1", "name": "contact_human", "arguments": "{\"message\":\"hi\"}"}]},
        {"role": "tool", "tool_call_id": "c1", "name": "contact_human", "content": "sent"},
        {"role": "assistant", "content": "waiting"},
    ]
    caller = FakeCaller()
    res = asyncio.run(run_turn(request("User: 3pm works", history), demo_model(), caller))
    assert caller.calls == [("chat_with_agent", {"agent_name": "dispatcher", "question": "Plumber says: 3pm works"})]
    assert "PydanticAI" in res.reply


def test_http_endpoint(monkeypatch):
    seen = []

    class Caller(FakeCaller):
        def __init__(self, url, client):
            super().__init__()
            seen.append(url)

    monkeypatch.setattr(app_module, "HttpToolCaller", Caller)
    with TestClient(app_module.app) as client:
        body = request("Pending messages: dispatcher: hello").model_dump()
        body["callback"]["url"] = "http://mahilo/engine/turns/t1/tools"
        res = client.post("/v1/turns", json=body)
    assert res.status_code == 200
    assert res.json()["tool_calls"][0]["name"] == "contact_human"
    assert seen == ["http://mahilo/engine/turns/t1/tools"]
