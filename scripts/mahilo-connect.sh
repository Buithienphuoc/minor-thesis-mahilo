#!/usr/bin/env bash
# Mở CLI nối vào một agent, giống `mahilo connect --agent-name X` của bản Python.
#
#   scripts/mahilo-connect.sh --agent-name SalesAgent [--url http://localhost:8000] [--json]
#
# Lần đầu sẽ gọi sbt để build module cli và lưu classpath vào target/cli-classpath.txt;
# các lần sau chạy thẳng bằng java (nhanh, và mở được nhiều terminal cùng lúc
# mà không tranh nhau khoá của sbt).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CP_FILE="$ROOT/target/cli-classpath.txt"

if [ ! -s "$CP_FILE" ] || [ -n "${MAHILO_CLI_REBUILD:-}" ]; then
  echo "Đang build CLI (chỉ lần đầu)..." >&2
  mkdir -p "$ROOT/target"
  (cd "$ROOT" && sbt -batch -error "export cli/Runtime/fullClasspath") | tail -1 > "$CP_FILE"
fi

exec java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dstdin.encoding=UTF-8 \
  -cp "$(cat "$CP_FILE")" mahilo.cli.MahiloCli connect "$@"
