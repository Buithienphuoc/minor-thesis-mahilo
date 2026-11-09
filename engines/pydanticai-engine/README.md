# pydanticai-engine

Service Python chạy **PydanticAI** làm engine cho agent của Mahilo-Scala. Mahilo-Scala gọi nó qua REST theo hợp đồng ở [`docs/remote-engine-contract.md`](../../docs/remote-engine-contract.md). Phía Scala, đây chỉ là một hiện thực của port `AgentEngine` (`RemoteAgentEngine` trong module `adapter-remote-engine`), ngang hàng với `ToolLoopEngine` và `LangGraph4jEngine`.

Engine chỉ "suy nghĩ". Mọi tool (`chat_with_agent`, `contact_human`, tool tự viết) đều chạy ở Mahilo-Scala qua callback, nên broker, policy, telemetry vẫn áp dụng như với agent thường.

Đây là phần thay thế tạm cho `mahilo/integrations/pydanticai` bên Mahilo Python. Khi viết lại PydanticAI bằng Scala, chỉ cần một hiện thực `AgentEngine` mới trong Scala và đổi một dòng ở `ServerMain`; lõi không đổi.

## Cấu trúc

| File | Vai trò |
|---|---|
| `contract.py` | Kiểu dữ liệu của hợp đồng REST v1 |
| `engine.py` | Lõi: chạy một lượt bằng `pydantic_ai.Agent`. Chỉ biết port `ToolCaller` |
| `history.py` | Chuyển tin định dạng OpenAI ↔ message của PydanticAI |
| `mahilo_client.py` | Adapter `ToolCaller`: gọi callback HTTP về Mahilo-Scala |
| `profiles.py` | Cấu hình riêng từng agent: output có cấu trúc (`ProductAgent` → `FeatureAnalysis`, giữ nguyên từ `pydanticai_product_agent.py`) và chỉ dẫn thêm vào prompt |
| `demo_model.py` | `FunctionModel` giả lập vai `plumber` và `ProductAgent`, chạy không cần API key |
| `app.py` | FastAPI: `POST /v1/turns`, `GET /health` |

## Chạy

```bash
cd engines/pydanticai-engine
python -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"            # thêm ",openai" nếu dùng model OpenAI thật
pytest                              # 4 test

PYDANTICAI_MODEL=demo uvicorn mahilo_pydanticai.app:app --port 8001
# hoặc: PYDANTICAI_MODEL=openai:gpt-4o-mini OPENAI_API_KEY=... uvicorn mahilo_pydanticai.app:app --port 8001
```

Rồi chạy Mahilo-Scala và chỉ định agent nào dùng engine này:

```bash
MAHILO_LLM=scripted \
MAHILO_PYDANTICAI_URL=http://localhost:8001 \
MAHILO_PYDANTICAI_AGENTS=plumber \
sbt "bootstrap/runMain mahilo.bootstrap.ServerMain"

curl -X POST localhost:8000/chat/dispatcher -d 'Toilet nhà tôi bị tắc'
curl -X POST localhost:8000/chat/plumber    -d 'Chiều nay 3h tôi đến được'
curl localhost:8000/messages
```

Hoặc chạy cả hai cùng lúc bằng `scripts/run-pydanticai-demo.sh` ở gốc repo.

Ví dụ team_of_agents (ProductAgent chạy ở đây, trả kết quả `FeatureAnalysis`):

```bash
MAHILO_EXAMPLE=team MAHILO_LLM=scripted MAHILO_PYDANTICAI_URL=http://localhost:8001 \
  sbt "bootstrap/runMain mahilo.bootstrap.ServerMain"
scripts/mahilo-connect.sh --agent-name SalesAgent     # gõ: "Phân tích các cuộc gọi", rồi "hỏi product đi"
```

Thêm agent mới: khai báo agent và tool ở phía Scala, rồi (tuỳ chọn) thêm một `Profile` cho nó trong `profiles.py`. Xem [user guide, mục 9b](../../docs/user-guide.md#9b-pydanticai-service-python-qua-rest).

Phiên bản: `pydantic-ai-slim==2.57.0`, `fastapi==0.156.3`, `uvicorn==0.54.0`, Python ≥ 3.11 (đã chạy với 3.13).
