#!/usr/bin/env bash
# Chạy engine PydanticAI (Python, model demo) + Mahilo-Scala (LLM giả lập), rồi đi hết
# kịch bản Dispatcher ↔ Plumber, trong đó plumber "suy nghĩ" bằng PydanticAI.
# Cần: python3 ≥ 3.11, sbt, curl.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="$ROOT/engines/pydanticai-engine"

cd "$ENGINE"
[ -d .venv ] || python3 -m venv .venv
. .venv/bin/activate
pip install -q -e .
PYDANTICAI_MODEL=demo uvicorn mahilo_pydanticai.app:app --port 8001 > /tmp/mahilo-pydanticai.log 2>&1 &
PY_PID=$!

cd "$ROOT"
MAHILO_LLM=scripted MAHILO_DB=/tmp/mahilo-demo.db MAHILO_PYDANTICAI_URL=http://localhost:8001 \
  sbt -batch "bootstrap/runMain mahilo.bootstrap.ServerMain" > /tmp/mahilo-scala.log 2>&1 &
SBT_PID=$!
trap 'kill $PY_PID $SBT_PID 2>/dev/null || true' EXIT

echo "Đang chờ Mahilo-Scala khởi động..."
until curl -sf localhost:8000/health > /dev/null; do sleep 2; done

echo; echo "👤 Khách → dispatcher";  curl -s -X POST localhost:8000/chat/dispatcher -d 'Toilet nhà tôi bị tắc'; echo
sleep 2
echo; echo "🔧 Thợ → plumber (PydanticAI)"; curl -s -X POST localhost:8000/chat/plumber -d 'Chiều nay 3h tôi đến được'; echo
sleep 2
echo; echo "=== /messages ==="; curl -s localhost:8000/messages; echo
