# Hướng dẫn sử dụng Mahilo-Scala

Tài liệu này đi từng bước cho từng tình huống sử dụng. Mọi lệnh chạy từ thư mục gốc của repo, trừ khi ghi khác.

| # | Tình huống | Cần API key? |
|---|-----------|:---:|
| 0 | [Cài đặt](#0-cài-đặt) | |
| 1 | [Demo nhanh trong 1 terminal](#1-demo-nhanh-trong-1-terminal) | Không |
| 2 | [Ví dụ Dispatcher ↔ Plumber: server + client cho từng agent](#2-ví-dụ-dispatcher--plumber-server--client-cho-từng-agent) | Không |
| 3 | [Ví dụ Team of Agents: 3 agent, 3 engine (native, LangGraph4j, PydanticAI)](#3-ví-dụ-team-of-agents-3-agent-3-engine) | Không |
| 4 | [Dùng LLM thật: OpenAI, Ollama, LangChain4j](#4-dùng-llm-thật) | Có (trừ Ollama) |
| 5 | [Gọi bằng REST thay cho CLI](#5-gọi-bằng-rest-thay-cho-cli) | |
| 6 | [Quan sát hệ thống: tin nhắn, hội thoại, metrics](#6-quan-sát-hệ-thống) | |
| 7 | [Policy: bật, tắt, tự viết](#7-policy) | |
| 8 | [Tạo agent và tool của riêng bạn](#8-tạo-agent-và-tool-của-riêng-bạn) | |
| 9 | [Đổi engine cho một agent: LangGraph4j, PydanticAI, engine tự viết](#9-đổi-engine-cho-một-agent) | |
| 10 | [Lưu trữ SQLite, khởi động lại, ký HMAC](#10-lưu-trữ-khởi-động-lại-ký-hmac) | |
| 11 | [Chạy test](#11-chạy-test) | |
| 12 | [Xử lý sự cố](#12-xử-lý-sự-cố) | |

Bản đồ nhanh:

```
                  ┌──────────── Mahilo-Scala server (cổng 8000) ────────────┐
CLI / WebSocket ─►│  /ws/<agent>      ChatUseCase ─► AgentRuntime ─► engine │──► LLM (OpenAI, Ollama, giả lập)
curl / REST ─────►│  /chat/<agent>    MessageBroker ─► hàng đợi ─► agent    │
                  │  /messages, /metrics, /policies ...                     │◄─► pydanticai-engine (Python, cổng 8001)
                  └──────────────────────────────────────────────────────────┘
```

---

## 0. Cài đặt

1. Cài **JDK 17+** (`java -version`) và **sbt** 1.10+ (https://www.scala-sbt.org/download).
2. (Chỉ cần cho tình huống 3 và 9 với PydanticAI) Cài **Python 3.11+**.
3. Clone và build:

   ```bash
   git clone https://github.com/Buithienphuoc/minor-thesis-mahilo.git
   cd minor-thesis-mahilo
   sbt compile
   ```

   Lần đầu sbt tải thư viện nên mất vài phút.

4. (Tuỳ chọn) Cài service PydanticAI:

   ```bash
   cd engines/pydanticai-engine
   python3 -m venv .venv
   . .venv/bin/activate          # Windows: .venv\Scripts\activate
   pip install -e ".[dev]"        # thêm ",openai" nếu muốn dùng model OpenAI thật
   cd ../..
   ```

> Nếu Maven Central trả lỗi 429 (bị giới hạn tốc độ), xem [mục 12](#12-xử-lý-sự-cố).

---

## 1. Demo nhanh trong 1 terminal

Mục đích: xem toàn bộ luồng chạy ngay trong một tiến trình, không cần server.

```bash
sbt "bootstrap/runMain mahilo.bootstrap.DemoMain"
# hoặc cho plumber chạy bằng LangGraph4j:
sbt "bootstrap/runMain mahilo.bootstrap.DemoMain --langgraph4j"
```

Kịch bản in ra: khách báo "toilet bị tắc" cho `dispatcher` → dispatcher gọi tool `chat_with_agent` gửi cho `plumber` → plumber xử lý hộp thư, gọi `contact_human` hỏi thợ → thợ trả lời "3h chiều" → plumber gửi lại dispatcher → dispatcher báo khách.

"Bộ não" ở đây là `DemoBrain`, một LLM giả lập theo kịch bản, nên không cần mạng hay API key.

---

## 2. Ví dụ Dispatcher ↔ Plumber: server + client cho từng agent

Cách này giống hệt bản Python gốc: chạy server trước, rồi mỗi agent có một người (một terminal) nối vào.

**Bước 1: chạy server** (terminal 1)

```bash
MAHILO_LLM=scripted sbt "bootstrap/runMain mahilo.bootstrap.ServerMain"
```

Đợi đến khi thấy:

```
Ví dụ: dispatcher  •  agent: dispatcher, plumber, mold_specialist  •  active: dispatcher
HTTP: http://0.0.0.0:8000/  •  WebSocket: ws://localhost:8000/ws/<agent>  •  CLI: scripts/mahilo-connect.sh --agent-name <agent>
Mahilo-Scala đang chạy. Ctrl+C để dừng.
```

`MAHILO_LLM=scripted` dùng bộ não giả lập. Bỏ biến này đi và đặt `OPENAI_API_KEY` để dùng GPT thật (xem [mục 4](#4-dùng-llm-thật)).

**Bước 2: khách hàng nối vào dispatcher** (terminal 2)

```bash
scripts/mahilo-connect.sh --agent-name dispatcher
```

Lần đầu script gọi sbt để build CLI (khoảng 30 giây), các lần sau chạy ngay. Gõ `sbt "cli/run connect --agent-name dispatcher"` cũng được, nhưng mỗi terminal sẽ mở thêm một sbt.

**Bước 3: thợ sửa ống nước nối vào plumber** (terminal 3)

```bash
scripts/mahilo-connect.sh --agent-name plumber
```

**Bước 4: trò chuyện**

Kết quả thật khi chạy với `MAHILO_LLM=scripted`:

Terminal 2 (khách, agent `dispatcher`):

```
Enter message for dispatcher (or 'quit' to exit): Toilet nhà tôi bị tắc

dispatcher: Mình đã liên hệ thợ sửa ống nước, sẽ báo lại bạn ngay khi có phản hồi.

dispatcher: Cập nhật từ thợ: Plumber says: Chiều nay 3h tôi đến được
```

Terminal 3 (thợ, agent `plumber`):

```
plumber: Điều phối viên hỏi: Customer reports: Toilet nhà tôi bị tắc. When can you come?

Enter message for plumber (or 'quit' to exit): Chiều nay 3h tôi đến được

plumber: Đã chuyển lời của anh cho điều phối viên.
```

Thứ tự: khách gõ ở terminal 2 → dispatcher gọi `chat_with_agent(plumber)` → plumber được kích hoạt, xử lý hộp thư, gọi `contact_human` → câu hỏi hiện ở terminal 3 → thợ trả lời → plumber gửi lại dispatcher → dispatcher dùng `contact_human` báo khách ở terminal 2.

Gõ `quit` để thoát.

Ghi chú:
- Lúc đầu chỉ `dispatcher` active. Nếu thợ gõ tin cho `plumber` trước khi agent này được kích hoạt thì sẽ nhận `Agent plumber is not active.` (vẫn nối được, chỉ là chưa chat được). Agent được kích hoạt tự động khi có agent khác nhắn cho nó, hoặc bằng `curl -X POST localhost:8000/agents/plumber/activate`.
- `--url http://host:port` để nối tới server khác. `--json` gửi tin dạng chuỗi JSON (có dấu nháy), giống hệt client Python `mahilo connect`.

---

## 3. Ví dụ Team of Agents: 3 agent, 3 engine

Bản port của `examples/team_of_agents` trong Mahilo Python:

| Agent | Vai trò | Engine (bản Python) | Engine (bản Scala) |
|---|---|---|---|
| `SalesAgent` | nói chuyện trực tiếp với người dùng, có dữ liệu cuộc gọi và nguồn lead | `BaseAgent` | engine gốc (`ToolLoopEngine`) |
| `MarketingAgent` | lên kế hoạch nội dung | `LangGraphAgent` | **LangGraph4j** (`LangGraph4jEngine`) |
| `ProductAgent` | phân tích yêu cầu tính năng, output có cấu trúc `FeatureAnalysis` | `PydanticAIAgent` | **PydanticAI** (service Python riêng, gọi qua REST) |

Cả ba được bật sẵn khi khởi động, giống `control_plane.py`.

**Bước 1: chạy service PydanticAI** (terminal 1)

```bash
cd engines/pydanticai-engine && . .venv/bin/activate
PYDANTICAI_MODEL=demo uvicorn mahilo_pydanticai.app:app --port 8001
```

`PYDANTICAI_MODEL=demo` dùng `FunctionModel` giả lập của PydanticAI. Đổi thành `openai:gpt-4o-mini` (kèm `OPENAI_API_KEY`) để dùng model thật.

**Bước 2: chạy server với ví dụ `team`** (terminal 2)

```bash
MAHILO_EXAMPLE=team MAHILO_LLM=scripted MAHILO_PYDANTICAI_URL=http://localhost:8001 \
  sbt "bootstrap/runMain mahilo.bootstrap.ServerMain"
```

Đợi dòng `Ví dụ: team • agent: ProductAgent, MarketingAgent, SalesAgent • active: ProductAgent, MarketingAgent, SalesAgent`.

Không đặt `MAHILO_PYDANTICAI_URL` thì server vẫn chạy, in cảnh báo, và ProductAgent tạm dùng engine gốc.

**Bước 3: mở client cho SalesAgent và MarketingAgent** (terminal 3 và 4)

```bash
scripts/mahilo-connect.sh --agent-name SalesAgent
scripts/mahilo-connect.sh --agent-name MarketingAgent
```

(Có thể mở thêm terminal 5 cho `ProductAgent` nếu muốn đóng vai product manager.)

**Kịch bản A: Sales hỏi Product** (gõ ở terminal SalesAgent)

```
Enter message for SalesAgent (or 'quit' to exit): Phân tích các cuộc gọi bán hàng gần đây giúp mình

SalesAgent: Kết quả phân tích: I've analyzed last month's calls. 40 percent of all enterprise customers
are interested in an integration with mahilo. Bạn có muốn mình hỏi ProductAgent về tình trạng tính năng
mahilo integration không?

Enter message for SalesAgent (or 'quit' to exit): Ok, hỏi product đi

SalesAgent: Mình đã hỏi ProductAgent, sẽ báo lại khi có trả lời.

SalesAgent: ProductAgent trả lời: [PydanticAI] Based on analysis, mahilo integration is in our Q3 roadmap,
coming in a few months.
```

Phía sau: SalesAgent gọi tool `collect_feature_feedback`, rồi `chat_with_agent(ProductAgent)`. ProductAgent nhận tin trong hộp thư; Mahilo-Scala gửi lượt này sang service Python (`POST /v1/turns`). PydanticAI gọi tool `analyze_feature_request` qua callback về Mahilo, trả lời SalesAgent bằng `chat_with_agent`, rồi kết thúc lượt bằng output có cấu trúc `FeatureAnalysis`. SalesAgent nhận tin, dùng `contact_human` để báo bạn.

Ở terminal 1 (Python) bạn thấy `POST /v1/turns HTTP/1.1 200 OK`.

**Kịch bản B: Marketing hỏi Sales** (gõ ở terminal MarketingAgent)

```
Enter message for MarketingAgent (or 'quit' to exit): Sắp có tính năng mới, lên kế hoạch nội dung giúp mình

MarketingAgent: Mình đã lên lịch nội dung (tháng 1: Multi-Agent Capabilities, tháng 2: Integrations)
và đang hỏi SalesAgent kênh nào nhiều lead.

MarketingAgent: Đề xuất: đẩy lịch nội dung trên kênh mà SalesAgent báo. Chi tiết: Lead sources:
We've been getting more leads from Reddit lately.
```

Phía sau: MarketingAgent chạy trong `StateGraph` của LangGraph4j (agent ⇄ tools), gọi `research_trending_topics`, `generate_content_calendar`, rồi `chat_with_agent(SalesAgent, "Which channels have brought in more leads?")`. SalesAgent chạy `analyze_lead_sources` và trả lời. MarketingAgent nhận được thì gửi đề xuất cho bạn.

**Bước 4: xem lại toàn bộ tin giữa các agent**

```bash
curl -s localhost:8000/messages | python3 -m json.tool
```

Bạn sẽ thấy 4 tin, tất cả `processed`: Sales→Product, Product→Sales, Marketing→Sales, Sales→Marketing.

> Với `MAHILO_LLM=scripted`, các câu trả lời đến từ `TeamBrain.scala` (bộ não theo kịch bản). Nó nhận diện các từ khoá như "cuộc gọi"/"call", "product", "insight". Muốn trò chuyện tự do thì dùng LLM thật (mục 4): prompt của từng agent được giữ nguyên từ bản Python.

---

## 4. Dùng LLM thật

Cả hai ví dụ đều chạy được với LLM thật. Chỉ cần đổi biến môi trường của server, không phải sửa code:

**OpenAI** (adapter `OpenAiCompatibleLlmClient`, gọi thẳng REST):

```bash
export OPENAI_API_KEY=sk-...
MAHILO_LLM_MODEL=gpt-4o-mini sbt "bootstrap/runMain mahilo.bootstrap.ServerMain"
```

**Qua LangChain4j** (adapter `LangChain4jLlmClient`, kết quả như nhau nhưng đi qua thư viện LangChain4j):

```bash
MAHILO_LLM=langchain4j OPENAI_API_KEY=sk-... sbt "bootstrap/runMain mahilo.bootstrap.ServerMain"
```

**Ollama / vLLM / LM Studio** (mọi server có API kiểu OpenAI):

```bash
ollama pull llama3.1
MAHILO_LLM_BASE_URL=http://localhost:11434/v1 MAHILO_LLM_MODEL=llama3.1 OPENAI_API_KEY=ollama \
  sbt "bootstrap/runMain mahilo.bootstrap.ServerMain"
```

Model phải hỗ trợ tool calling.

**Team of Agents với model thật**: thêm `MAHILO_EXAMPLE=team`. MarketingAgent (LangGraph4j) tự dùng cùng model qua LangChain4j. Với ProductAgent, chạy service Python với `PYDANTICAI_MODEL=openai:gpt-4o-mini` (cần `pip install -e ".[openai]"`).

Tất cả biến môi trường của server:

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `MAHILO_EXAMPLE` | `dispatcher` | bộ agent: `dispatcher` hoặc `team` |
| `MAHILO_LLM` | `openai` | `openai`, `langchain4j`, hoặc `scripted` (giả lập) |
| `MAHILO_ENGINE` | `native` | engine mặc định của ví dụ dispatcher: `native` hoặc `langgraph4j` |
| `OPENAI_API_KEY` | | key cho `openai`/`langchain4j` |
| `MAHILO_LLM_MODEL` | `gpt-4o-mini` | tên model |
| `MAHILO_LLM_BASE_URL` | `https://api.openai.com/v1` | đổi để dùng Ollama, vLLM... |
| `MAHILO_DB` | `mahilo.db` | file SQLite |
| `MAHILO_SECRET_KEY` | | bật ký HMAC cho tin giữa các agent |
| `MAHILO_POLICIES` | | policy bật sẵn, vd `anti_loop,message_length` |
| `PORT` | `8000` | cổng HTTP/WebSocket |
| `MAHILO_PYDANTICAI_URL` | | URL service PydanticAI, vd `http://localhost:8001` |
| `MAHILO_PYDANTICAI_AGENTS` | `plumber` / `ProductAgent` | agent nào chạy bằng PydanticAI (phân tách bằng dấu phẩy) |
| `MAHILO_PUBLIC_URL` | `http://localhost:PORT` | URL mà service PydanticAI dùng để gọi ngược về Mahilo |

---

## 5. Gọi bằng REST thay cho CLI

Hữu ích khi viết script hoặc nối với frontend.

```bash
# danh sách agent và trạng thái
curl localhost:8000/agents

# kích hoạt một agent
curl -X POST localhost:8000/agents/plumber/activate

# chat trực tiếp (body là text thuần)
curl -X POST localhost:8000/chat/dispatcher -d 'Toilet nhà tôi bị tắc'
# → {"response":"...","activated_agents":["dispatcher","plumber"]}
```

Lưu ý: tin `contact_human` (agent chủ động nhắn cho người) chỉ đến qua WebSocket `ws://localhost:8000/ws/<agent>`, nên phía "người" vẫn cần một kết nối WebSocket: CLI, `websocat ws://localhost:8000/ws/plumber`, hoặc trình duyệt.

---

## 6. Quan sát hệ thống

| Endpoint | Cho biết gì |
|---|---|
| `GET /health` | server còn sống |
| `GET /messages?sender=&recipient=&start_time=&end_time=&limit=` | tin giữa các agent và trạng thái (`pending`, `processed`, `failed`) |
| `GET /conversations/{a}/{b}?limit=` | hội thoại giữa hai agent, cũ → mới |
| `GET /metrics?agent_id=` | bộ đếm: số tin gửi, nhận, retry, lời gọi tool... |
| `GET /policies` | các policy và trạng thái bật/tắt |
| `GET /policies/violations?limit=&policy_name=` | các lần vi phạm policy |

Ví dụ:

```bash
curl 'localhost:8000/messages?sender=SalesAgent'
curl localhost:8000/conversations/SalesAgent/ProductAgent
curl 'localhost:8000/metrics?agent_id=MarketingAgent'
```

---

## 7. Policy

Policy kiểm tra mỗi tin giữa các agent **trước khi** nó vào hàng đợi. Tin bị chặn thì agent gửi nhận ngay lý do trong kết quả tool.

Policy có sẵn: `anti_loop` (chặn hai agent lặp lại cùng một câu), `message_length`, `relevance` và `toxicity` (hai cái sau dùng LLM làm giám khảo).

**Bật lúc khởi động:**

```bash
MAHILO_POLICIES=anti_loop,message_length MAHILO_LLM=scripted sbt "bootstrap/runMain mahilo.bootstrap.ServerMain"
```

**Bật/tắt lúc đang chạy:**

```bash
curl -X POST localhost:8000/policies/message_length/enable
curl -X POST localhost:8000/policies/message_length/disable
curl localhost:8000/policies/violations
```

**Tự viết policy** (trong code, ví dụ ở `ServerMain` sau khi có `mahilo`):

```scala
import mahilo.domain.policy.{Policy, PolicyRule}

val noShouting = Policy(
  name = "no_shouting",
  description = "Không viết toàn chữ hoa",
  rule = PolicyRule.Heuristic((envelope, _) =>
    Option.when(envelope.payload.exists(_.isLetter) && envelope.payload == envelope.payload.toUpperCase)("Don't shout")),
  enabled = true
)
mahilo.admin.addPolicy(noShouting)
```

`Heuristic` trả `Some(lý do)` để chặn, `None` để cho qua. Muốn LLM chấm thì dùng `PolicyRule.NaturalLanguage("mô tả quy tắc")`. Xem test mẫu ở `ConversationFlowSpec`.

---

## 8. Tạo agent và tool của riêng bạn

Ví dụ: thêm một bộ agent "nhà hàng" với `waiter` và `chef`.

**Bước 1: khai báo agent và tool** ở `modules/bootstrap/src/main/scala/mahilo/bootstrap/templates/RestaurantTemplates.scala`:

```scala
package mahilo.bootstrap.templates

import cats.effect.IO
import mahilo.application.service.{AgentDefinition, CustomTool}
import mahilo.domain.AgentName
import mahilo.domain.agent.AgentSpec
import mahilo.domain.tool.{ToolParam, ToolSpec}

object RestaurantTemplates:
  val Waiter = AgentName("waiter")
  val Chef   = AgentName("chef")

  val waiter = AgentSpec(Waiter, "waiter",
    description = "You talk to guests, take orders and ask the chef about dishes.",
    shortDescription = "Talks to guests")

  val chef = AgentSpec(Chef, "chef",
    description = "You answer questions about dishes. Use check_stock before promising a dish.",
    shortDescription = "Knows the menu and the kitchen")

  // Tool = mô tả (ToolSpec, thuần) + hàm chạy (IO). Tham số đến dưới dạng Map[String, String].
  val checkStock = CustomTool(
    ToolSpec("check_stock", "Check if an ingredient is in stock", List(ToolParam("ingredient", "Ingredient name"))),
    args => IO.pure(if args.get("ingredient").contains("salmon") then "Out of stock" else "In stock")
  )

  val definitions = List(AgentDefinition(waiter), AgentDefinition(chef, tools = List(checkStock)))
  val startActive = List(Waiter)
```

- `description` là phần vai trò trong system prompt. Mahilo tự thêm danh sách agent có thể liên lạc và hướng dẫn dùng tool.
- `canContact = ContactRule.Only(Set(...))` để giới hạn agent này được nhắn cho ai.
- Mỗi agent tự có 3 tool chuẩn: `chat_with_agent`, `contact_human`, `get_message_history`. Bạn chỉ khai báo tool riêng.

**Bước 2: đăng ký ví dụ** trong `ServerMain.buildExample`, thêm một nhánh:

```scala
case "restaurant" =>
  Resource.pure(Example(RestaurantTemplates.definitions, RestaurantTemplates.startActive))
```

**Bước 3: chạy với LLM thật** (bộ não giả lập chỉ biết kịch bản có sẵn):

```bash
MAHILO_EXAMPLE=restaurant OPENAI_API_KEY=sk-... sbt "bootstrap/runMain mahilo.bootstrap.ServerMain"
scripts/mahilo-connect.sh --agent-name waiter
```

**Bước 4 (nên làm): viết test** theo mẫu `TeamOfAgentsSpec`. Dùng `TestKit.system(agents = ..., startActive = ..., humans = ...)` với `ScriptedLlmClient` để kiểm tra luồng mà không cần mạng.

---

## 9. Đổi engine cho một agent

Engine là "bộ máy suy nghĩ" của agent (port `AgentEngine`). Lõi Mahilo không biết engine nào đang chạy. Tool, broker, policy, telemetry luôn chạy trong lõi. Mỗi `AgentDefinition` có `engine: Option[AgentEngine]`, để `None` thì dùng engine gốc.

### 9a. LangGraph4j

Toàn bộ agent của ví dụ dispatcher dùng LangGraph4j:

```bash
MAHILO_ENGINE=langgraph4j MAHILO_LLM=scripted sbt "bootstrap/runMain mahilo.bootstrap.ServerMain"
```

Trong code, cho một agent cụ thể:

```scala
for
  model  <- ChatModelFromLlmClient.resource(llm)     // hoặc LangChain4jModels.openAi(key, "gpt-4o-mini", None)
  engine <- LangGraph4jEngine.resource(model)
yield AgentDefinition(mySpec, myTools, engine = Some(engine))
```

**Tự vẽ graph:** truyền `buildGraph` của riêng bạn. Hàm nhận `GraphParts` (model, danh sách tool, `executeTool`, `maxIterations`) và trả về `StateGraph[MahiloGraphState]`. Graph mặc định `ReactGraph.build` (agent ⇄ tools) ở `modules/adapter-langchain4j/.../LangGraph4jEngine.scala` là mẫu tốt nhất để sao chép. Ví dụ, thêm một node "reflect" trước `END`:

```scala
def myGraph(parts: GraphParts): StateGraph[MahiloGraphState] =
  // sao chép ReactGraph.build, thêm .addNode("reflect", ...) và đổi cạnh "end" -> "reflect", "reflect" -> END
  ???

LangGraph4jEngine.resource(model, buildGraph = myGraph)
```

Luôn chạy tool qua `parts.executeTool`, đừng gọi thẳng, để tin giữa các agent vẫn đi qua broker và policy.

### 9b. PydanticAI (service Python qua REST)

1. Chạy service: `PYDANTICAI_MODEL=demo uvicorn mahilo_pydanticai.app:app --port 8001` (thư mục `engines/pydanticai-engine`).
2. Chọn agent nào dùng nó:

   ```bash
   MAHILO_PYDANTICAI_URL=http://localhost:8001 MAHILO_PYDANTICAI_AGENTS=plumber MAHILO_LLM=scripted \
     sbt "bootstrap/runMain mahilo.bootstrap.ServerMain"
   ```

   Hoặc chạy cả hai bằng một lệnh: `scripts/run-pydanticai-demo.sh`.
3. Tuỳ biến riêng cho từng agent (output có cấu trúc, chỉ dẫn thêm) ở `engines/pydanticai-engine/mahilo_pydanticai/profiles.py`:

   ```python
   class Diagnosis(BaseModel):
       problem: str
       urgency: int

   PROFILES = {
       "ProductAgent": Profile(output_type=FeatureAnalysis),
       "plumber": Profile(output_type=Diagnosis),
   }
   ```

   Tool **không** khai báo ở Python: chúng được khai báo ở Scala và gửi sang trong mỗi lượt.
4. Nếu service Python chạy trên máy khác, đặt `MAHILO_PUBLIC_URL` thành địa chỉ mà máy đó gọi được về Mahilo.

### 9c. Engine tự viết bằng ngôn ngữ khác

Bất kỳ service nào làm đúng [`docs/remote-engine-contract.md`](remote-engine-contract.md) đều thay được PydanticAI: nhận `POST /v1/turns`, gọi tool bằng `POST {callback.url}/{tool}`, trả `{reply, tool_calls, stopped_early}`. Sau này khi viết lại PydanticAI bằng Scala, bạn chỉ cần một class hiện thực `AgentEngine` và đổi một dòng trong `ServerMain`.

---

## 10. Lưu trữ, khởi động lại, ký HMAC

- Tin giữa các agent và session hội thoại lưu ở SQLite (`MAHILO_DB`, mặc định `mahilo.db`).
- **Khởi động lại:** dừng server (Ctrl+C) rồi chạy lại với cùng `MAHILO_DB`. Agent nhớ lại hội thoại cũ. Tin còn `pending` được xử lý tiếp ngay (không phải đợi poll).
- **Bắt đầu sạch:** xoá file, hoặc trỏ `MAHILO_DB` sang file mới.
- **Ký HMAC:** `MAHILO_SECRET_KEY=bi-mat-cua-ban`. Mỗi tin được ký khi gửi và kiểm tra khi nhận. Tin sai chữ ký bị đánh dấu `failed` và không được xử lý. Đổi key giữa hai lần chạy thì các tin `pending` cũ sẽ bị từ chối.

Xem nhanh bằng `sqlite3`:

```bash
sqlite3 mahilo.db 'select sender, recipient, state from messages;'
```

---

## 11. Chạy test

```bash
sbt test                                    # 30 test Scala: domain, adapter, luồng hội thoại, HTTP/WS, engine REST, team_of_agents, CLI
sbt "bootstrap/testOnly *TeamOfAgentsSpec"  # chỉ một bộ test

cd engines/pydanticai-engine && pytest      # 4 test Python của service PydanticAI
```

Test chạy hoàn toàn offline. LLM được giả lập bằng `ScriptedLlmClient`, engine REST được giả lập bằng một server http4s nhỏ.

---

## 12. Xử lý sự cố

| Triệu chứng | Cách xử lý |
|---|---|
| sbt báo `429 Too Many Requests` khi tải thư viện | Tạo `~/.sbt/repositories` với nội dung `[repositories]`, `local`, `google: https://maven-central.storage-download.googleapis.com/maven2/` (mỗi dòng một mục), rồi chạy sbt với `-Dsbt.override.build.repos=true` |
| CLI báo `Không nối được tới ws://...` | Server chưa chạy, sai cổng (`--url`), hoặc sai tên agent (phân biệt hoa thường: `SalesAgent`) |
| CLI nhận `Agent X is not active.` | `curl -X POST localhost:8000/agents/X/activate`, hoặc để agent khác nhắn cho X trước |
| Chữ tiếng Việt hiện thành `?` | Terminal không ở UTF-8. Repo đã có `.jvmopts` đặt UTF-8 cho sbt; trên Windows chạy `chcp 65001` |
| Server báo thiếu `OPENAI_API_KEY` | Đặt key, hoặc thêm `MAHILO_LLM=scripted` |
| `⚠️ Chưa đặt MAHILO_PYDANTICAI_URL` | Bình thường nếu bạn không chạy service Python. ProductAgent dùng engine gốc |
| ProductAgent không trả lời, log Scala có lỗi kết nối 8001 | Service PydanticAI chưa chạy. Kiểm tra bằng `curl localhost:8001/health` |
| Port 8000 bị chiếm | `PORT=8080 ...` cho server và `--url http://localhost:8080` cho CLI |
| Có proxy HTTP trong máy | `curl --noproxy '*' localhost:8000/...`, và đặt `NO_PROXY=localhost,127.0.0.1` cho Python |

Chưa hỗ trợ: voice (OpenAI Realtime + PyAudio) và xuất OpenTelemetry. Xem mục "Chưa port" trong [README](../README.md).
