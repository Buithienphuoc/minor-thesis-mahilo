# mahilo-scala

Bản port của [Mahilo](https://github.com/wjayesh/mahilo) (Python, commit `6c49bca`, v0.5.0) sang **Scala 3**, tổ chức theo **Hexagonal Architecture** (Ports & Adapters). Mục tiêu là code dễ đọc để học, nên mỗi file có chú thích tiếng Việt và ghi rõ nó tương ứng với chỗ nào trong bản Python.

Dự án này độc lập hoàn toàn với Health Care demo, không dùng chung code.

## Chạy thử trong 1 phút (không cần API key)

Cần JDK 17+ và [sbt](https://www.scala-sbt.org/download).

```bash
sbt test                                                        # 25 test (thêm 3 test pytest ở engines/pydanticai-engine)
sbt "bootstrap/runMain mahilo.bootstrap.DemoMain"               # kịch bản Dispatcher ↔ Plumber trong terminal
sbt "bootstrap/runMain mahilo.bootstrap.DemoMain --langgraph4j" # như trên, nhưng plumber chạy bằng LangGraph4j
```

Demo dùng một "bộ não" giả lập (`DemoBrain`) thay cho LLM, nên chạy offline mà vẫn đi qua đủ: tool calling, broker, hàng đợi, worker nền, `contact_human`, ký HMAC, telemetry.

Chạy server thật (HTTP + WebSocket, lưu SQLite):

```bash
export OPENAI_API_KEY=sk-...
sbt "bootstrap/runMain mahilo.bootstrap.ServerMain"

# hoặc không cần key:
MAHILO_LLM=scripted sbt "bootstrap/runMain mahilo.bootstrap.ServerMain"

curl -X POST localhost:8000/chat/dispatcher -d 'Toilet nhà tôi bị tắc'
curl localhost:8000/messages
# WebSocket: ws://localhost:8000/ws/dispatcher , ws://localhost:8000/ws/plumber
```

Biến môi trường (xem `ServerMain.scala`): `MAHILO_LLM` = `openai` | `langchain4j` | `scripted`; `MAHILO_ENGINE` = `native` | `langgraph4j`; `MAHILO_LLM_MODEL`, `MAHILO_LLM_BASE_URL` (Ollama, vLLM... đều được), `MAHILO_DB`, `MAHILO_SECRET_KEY`, `MAHILO_POLICIES=anti_loop,message_length`, `PORT`.

## Kiến trúc

```
                ┌──────────────────────── bootstrap (composition root) ────────────────────────┐
                │  ServerMain / DemoMain: chọn adapter nào cắm vào port nào                      │
                └───────────────────────────────────────────────────────────────────────────────┘
  ADAPTER VÀO (driving)                    LÕI                                 ADAPTER RA (driven)
 ┌──────────────────┐   port vào   ┌──────────────────────────────┐  port ra   ┌────────────────────────────┐
 │ adapter-http     │ ───────────► │ application                  │ ─────────► │ adapter-llm-openai         │ LlmClient
 │  REST + WebSocket│ ChatUseCase  │  AgentRuntime, ToolLoopEngine│ LlmClient  │ adapter-langchain4j        │ LlmClient, AgentEngine
 │ test, DemoMain   │ InboxUseCase │  MessageBroker, PolicyEngine │ AgentEngine│ adapter-persistence-sqlite │ MessageStore, SessionRepository
 └──────────────────┘ AdminUseCase │  DeliveryWorker              │ MessageStore adapter-local             │ in-memory, LLM giả lập, HMAC
                      Observation  │ ┌──────────────────────────┐ │ SessionRepo│ adapter-http               │ HumanGateway (WebSocket)
                      Query        │ │ domain (thuần, không IO) │ │ HumanGateway                            │
                                   │ └──────────────────────────┘ │ Telemetry  └────────────────────────────┘
                                   └──────────────────────────────┘ EnvelopeSigner
```

Quy tắc phụ thuộc, được sbt kiểm tra (module không khai báo `dependsOn` thì không import được):

| Module | Phụ thuộc | Thư viện ngoài |
|---|---|---|
| `domain` | không gì cả | **không có** |
| `application` | domain | cats-effect |
| `adapter-local` | application | không |
| `adapter-llm-openai` | application | circe (HTTP dùng JDK) |
| `adapter-persistence-sqlite` | application | sqlite-jdbc, circe |
| `adapter-http` | application | http4s (Ember), circe |
| `adapter-langchain4j` | application | **LangChain4j 1.22.0, LangGraph4j 1.9.5** |
| `adapter-remote-engine` | application | http4s-dsl, circe (gọi engine ngoài qua REST) |
| `bootstrap` | tất cả adapter | logback |

Không adapter nào biết adapter khác. Đổi SQLite sang Postgres, hay OpenAI sang LangChain4j, chỉ sửa `bootstrap`.

## Đọc code theo thứ tự nào

1. `modules/domain`: từ vựng của hệ thống. Bắt đầu với `Envelope.scala`, `DeliveryState.scala`, `ChatMessage.scala`, `AgentSpec.scala`, rồi `policy/` và `prompt/PromptBuilder.scala`. Toàn hàm thuần, test ở `DomainSpec`.
2. `modules/application/.../port`: các trait. `port/in` là thứ thế giới bên ngoài được gọi; `port/out` là thứ lõi cần thế giới bên ngoài cung cấp.
3. `modules/application/.../service/AgentRuntime.scala`: trái tim, tương đương phần lớn `agent.py`. Đọc `chat`, rồi `processInbox`, rồi `executeTool`.
4. `ToolLoopEngine.scala` (vòng lặp tool-calling), `MessageBroker.scala`, `PolicyEngine.scala`, `DeliveryWorker.scala`.
5. `Mahilo.scala`: lắp các service lại với nhau.
6. Một adapter bất kỳ, ví dụ `adapter-local/InMemoryMessageStore.scala` cạnh `adapter-persistence-sqlite/SqliteMessageStore.scala`: cùng một port, hai cách hiện thực.
7. `bootstrap/DemoMain.scala`: đặt breakpoint ở đây và chạy theo luồng.

## Đối chiếu Python → Scala

| Python | Scala | Ghi chú |
|---|---|---|
| `agent.py` `BaseAgent` (cấu hình) | `domain/agent/AgentSpec` | Template chỉ là một giá trị, không cần subclass |
| `agent.py` vòng lặp tool-calling (viết 2 lần) | `application/service/ToolLoopEngine` | Viết 1 lần, có giới hạn số vòng |
| `agent.py` `process_chat_message` / `process_queue_message` | `AgentRuntime.chat` / `processInbox` | Mutex mỗi agent |
| `agent.py` `contact_human` | `AgentRuntime.contactHuman` + port `HumanGateway` | Lõi không còn cầm WebSocket |
| `tools.py` `chat_with_agent` | `AgentRuntime.chatWithAgent` | Bỏ tham số `your_name` |
| `agent_manager.py` + `registry.py` (singleton) | `AgentDirectory`, `Mahilo.resource` | Không còn singleton toàn cục |
| `message_protocol.py` `MessageEnvelope`, `MessageType` | `domain/messaging/Envelope`, `MessageKind` | |
| `message_protocol.py` `MessageBroker` | `application/service/MessageBroker` | Event-driven, không poll |
| `message_store.py` | port `MessageStore` + `SqliteMessageStore`, `InMemoryMessageStore` | Thứ tự trả về ghi rõ trong port |
| `policy.py` | `domain/policy/*` (luật thuần) + `PolicyEngine` (gọi LLM) | |
| `session.py` | `domain/conversation/Session` + port `SessionRepository` | |
| `llm_config.py` (LiteLLM) | port `LlmClient` + `OpenAiCompatibleLlmClient` / `LangChain4jLlmClient` | |
| `integrations/langgraph/*` | port `AgentEngine` + `LangGraph4jEngine` | Xem mục dưới |
| `integrations/pydanticai/*` | port `AgentEngine` + `RemoteAgentEngine` → service Python `engines/pydanticai-engine` | Xem mục PydanticAI |
| `server.py` | `adapter-http` (`MahiloRoutes`, `WebSocketHumanGateway`) + `DeliveryWorker` | |
| `monitoring.py` | port `Telemetry` + `InMemoryTelemetry` | |
| `templates/centralized/*` | `bootstrap/templates/CentralizedTemplates` | Prompt giữ nguyên |

## LangChain4j và LangGraph4j

Bản Python có `integrations/langgraph`: bọc một `StateGraph` của LangGraph thành agent Mahilo, và tool `chat_with_agent` được bọc bằng `@tool` của langchain-core. Bản Scala giữ khả năng đó bằng hai thư viện JVM tương ứng, gom trong module `adapter-langchain4j`:

- **`LangChain4jLlmClient`** hiện thực port `LlmClient` bằng `ChatModel` của LangChain4j. Vai trò giống LiteLLM: đổi provider (OpenAI, Anthropic, Gemini, Ollama...) bằng cách đổi `ChatModel`.
- **`LangGraph4jEngine`** hiện thực port `AgentEngine` bằng một `StateGraph` của LangGraph4j. Graph mặc định (`ReactGraph`) là `agent ⇄ tools`, giống `ToolNode` + `tools_condition` trong ví dụ `langgraph_marketing_agent.py`. Muốn graph riêng thì truyền `buildGraph`.
- Tool trong graph không tự chạy mà gọi ngược về lõi Mahilo (`AgentTurn.executeTool`), nên agent LangGraph4j vẫn đi qua broker, policy, telemetry y như agent thường.
- Chọn engine theo từng agent: `AgentDefinition(spec, engine = Some(langGraphEngine))`. Trong demo, chỉ plumber chạy bằng LangGraph4j, dispatcher vẫn dùng vòng lặp gốc, và hai bên vẫn nói chuyện bình thường qua hàng đợi.
- `ChatModelFromLlmClient` làm chiều ngược lại (biến `LlmClient` giả lập thành `ChatModel`) để test và demo LangGraph4j không cần API key.

## PydanticAI: service Python riêng, nói chuyện qua REST

PydanticAI không phải phần lõi của Mahilo: bên Python nó chỉ nằm trong `integrations/pydanticai/` (một subclass adapter của `BaseAgent`). Chưa có bản tương đương trên JVM, nên ở đây nó chạy thành một **service Python riêng** ở [`engines/pydanticai-engine`](engines/pydanticai-engine/README.md) và chỉ nói chuyện với Mahilo-Scala qua REST:

```
Mahilo-Scala ── POST /v1/turns ──────────────────────────► pydanticai-engine (FastAPI + PydanticAI)
             ◄─ POST /engine/turns/{id}/tools/{tool} ──── (PydanticAI muốn gọi tool)
             ── {"result": ...} ─────────────────────────►
             ◄─ {"reply", "tool_calls"} ─────────────────
```

- Hợp đồng: [`docs/remote-engine-contract.md`](docs/remote-engine-contract.md).
- Phía Scala: module `adapter-remote-engine`, gồm `RemoteAgentEngine` (hiện thực port `AgentEngine`), `RemoteEngineRoutes` (route callback) và `RemoteTurnRegistry` (callback chỉ hợp lệ khi lượt còn đang chạy).
- Tool không chạy ở Python mà chạy ở Mahilo-Scala, nên broker, policy và telemetry vẫn áp dụng.
- Bật cho agent nào thì đặt `MAHILO_PYDANTICAI_URL=http://localhost:8001 MAHILO_PYDANTICAI_AGENTS=plumber`. Chạy cả hai cùng lúc: `scripts/run-pydanticai-demo.sh`.
- Sau này viết lại PydanticAI bằng Scala thì chỉ cần thêm một `AgentEngine` mới và đổi một dòng ở `ServerMain`. Lõi, hợp đồng và các agent khác giữ nguyên.

## Những gì đổi so với Python (sửa các lỗi trong bản phân tích, mục 9)

1. Không còn trạng thái dùng chung giữa các agent (`_voice_connections` ở mức class, mutable default).
2. `anti_loop` so với tin **mới nhất**: port `MessageStore.conversation` trả cũ → mới và ghi rõ trong kiểu.
3. `PolicyViolation` có thời điểm thật cho từng vi phạm.
4. Agent LangGraph xử lý được tin trong hàng đợi (bản Python bỏ qua và không ack).
5. Không tạo event loop lồng nhau; mọi thứ là `IO`.
6. Tool lỗi hoặc không tồn tại vẫn sinh tin `tool` trả lời, LLM không bị provider từ chối.
7. Tin bị policy chặn: LLM gửi nhận ngay lý do trong kết quả tool, thay vì "Message sent".
8. Người gửi lấy từ agent đang chạy, LLM không tự khai tên được nữa; kiểm tra `can_contact` và chặn tự nhắn cho mình.
9. Session lưu theo `name` của agent, và mỗi agent có `Mutex` nên chat trực tiếp và hộp thư không chạy chồng nhau.
10. Hàng đợi event-driven (`Queue` của cats-effect) thay cho poll SQLite mỗi giây; khởi động lại thì tự đánh thức agent còn tin pending.
11. Prompt và bộ tool thống nhất: chat trực tiếp trả lời thẳng người dùng, `contact_human` chỉ dùng khi xử lý hộp thư.
12. Có test cho lõi (25 test), log lỗi qua `Console` thay cho `print`.
13. Tin sai chữ ký bị đánh dấu `failed` (Python để `pending` mãi và quét lại mỗi giây).

## Chưa port

- **Voice (OpenAI Realtime)** và client PyAudio. Muốn thêm: một adapter WebSocket proxy trong `adapter-http`, cộng một hiện thực `HumanGateway` có thể "nói".
- **CLI `mahilo connect`**: dùng `curl`/`websocat` hoặc test `HttpAdapterSpec` làm mẫu.
- **Xuất OpenTelemetry**: hiện chỉ đếm trong RAM; thêm adapter otel4s hiện thực port `Telemetry`.
- Đường gọi OpenAI thật (`OpenAiCompatibleLlmClient`, `LangChain4jModels.openAi`) đã compile và test phần chuyển đổi JSON, nhưng chưa gọi thử với API key thật.
