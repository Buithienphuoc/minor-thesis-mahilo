# Hợp đồng REST: Remote Agent Engine (v1)

Hợp đồng giữa **Mahilo-Scala** (lõi) và một **engine chạy ngoài tiến trình** (hiện có: service
Python dùng PydanticAI ở `engines/pydanticai-engine`). Engine chỉ "suy nghĩ"; mọi tool
(`chat_with_agent`, `contact_human`, tool tự viết) đều chạy ở Mahilo-Scala, qua callback.
Nhờ vậy broker, policy và telemetry không bị bỏ qua dù engine viết bằng ngôn ngữ gì.

```
Mahilo-Scala                                         Engine (Python / PydanticAI)
     │  1. POST {engine}/v1/turns  (TurnRequest)               │
     │ ───────────────────────────────────────────────────────►│
     │                                                         │  PydanticAI chạy vòng lặp,
     │  2. POST {callback.url}/{tool_name}  (ToolInvocation)   │  muốn gọi tool thì gọi ngược
     │ ◄───────────────────────────────────────────────────────│
     │     200 {"result": "..."}                               │
     │ ───────────────────────────────────────────────────────►│
     │                (lặp 2 bao nhiêu lần cũng được)          │
     │  3. 200 TurnResponse                                    │
     │ ◄───────────────────────────────────────────────────────│
```

## 1. `POST /v1/turns` (engine phải hiện thực)

```json
{
  "turn_id": "6f1c...",
  "agent": { "name": "plumber", "kind": "plumber" },
  "messages": [
    { "role": "user", "content": "..." },
    { "role": "assistant", "content": null,
      "tool_calls": [{ "id": "c1", "name": "contact_human", "arguments": "{\"message\":\"...\"}" }] },
    { "role": "tool", "tool_call_id": "c1", "name": "contact_human", "content": "Sent ..." },
    { "role": "system", "content": "You are an AI agent of type plumber ..." },
    { "role": "user", "content": "Pending messages: dispatcher: ..." }
  ],
  "tools": [
    { "name": "chat_with_agent", "description": "...",
      "parameters": { "type": "object",
                      "properties": { "agent_name": { "type": "string", "description": "..." },
                                      "question":   { "type": "string", "description": "..." } },
                      "required": ["agent_name", "question"] } }
  ],
  "max_iterations": 10,
  "callback": { "url": "http://localhost:8000/engine/turns/6f1c.../tools" }
}
```

- `messages` theo định dạng OpenAI: lịch sử session, rồi system prompt, rồi tin mới (luôn là tin `user` cuối cùng).
- `max_iterations`: số lần gọi model tối đa trong lượt.

Trả về:

```json
{
  "reply": "Đã hỏi thợ, đang chờ trả lời.",
  "tool_calls": [
    { "id": "c7", "name": "contact_human", "arguments": "{\"message\":\"...\"}", "result": "Sent text message to human: ..." }
  ],
  "stopped_early": false
}
```

`tool_calls` là nhật ký các tool đã gọi trong lượt (theo thứ tự), để Mahilo ghi vào session.

## 2. `POST {callback.url}/{tool_name}` (Mahilo-Scala hiện thực)

```json
{ "tool_call_id": "c7", "arguments": { "message": "..." } }
```

Trả `200 {"result": "..."}`. Tool lỗi vẫn trả 200 với `result` mô tả lỗi (để model đọc và tự sửa).
`404` nếu `turn_id` không tồn tại hoặc đã kết thúc: callback chỉ hợp lệ trong lúc request (1) đang chạy.
