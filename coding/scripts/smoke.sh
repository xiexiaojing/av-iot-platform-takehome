#!/usr/bin/env bash
# 冒烟脚本：上线 -> 下发 -> 查终态（含幂等回放与离线失败断言）。任一步失败即 exit 1。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PORT="${PORT:-18080}"
HOST="${HOST:-127.0.0.1}"
CP="build/classes"
LOG="$(mktemp -t c2d-smoke.XXXXXX.log)"
SERVER_PID=""

cleanup() {
  if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
  rm -f "$LOG"
}
trap cleanup EXIT

fail() { echo "SMOKE FAIL: $*" >&2; exit 1; }
pass() { echo "SMOKE OK: $*"; }
cli() { java -cp "$CP" c2d.CliMain --host "$HOST" --port "$PORT" "$@"; }
json_get() { python3 -c "import json,sys; print(json.load(sys.stdin).get('$1',''))"; }

echo "== 1. 编译 =="
./build.sh || fail "编译失败"

echo "== 2. 启动服务 (port=$PORT) =="
java -cp "$CP" c2d.ServerMain --host "$HOST" --port "$PORT" --ack-ms 30 >"$LOG" 2>&1 &
SERVER_PID=$!

for _ in $(seq 1 60); do
  if curl -fsS "http://$HOST:$PORT/healthz" >/dev/null 2>&1; then break; fi
  sleep 0.1
done
curl -fsS "http://$HOST:$PORT/healthz" >/dev/null 2>&1 || fail "服务未就绪，日志：$(cat "$LOG")"

echo "== 3. 设备上线 =="
cli device online dev-smoke-1 >/dev/null || fail "设备上线失败"
pass "dev-smoke-1 已上线"

echo "== 4. 下发指令 =="
OUT="$(cli cmd dispatch dev-smoke-1 reboot --idem smoke-key-1 --timeout-ms 3000)" || fail "下发失败"
echo "  $OUT"
CMD_ID="$(printf '%s' "$OUT" | json_get command_id)" || fail "解析 command_id 失败"
[[ -n "$CMD_ID" ]] || fail "command_id 为空"
pass "command_id=$CMD_ID"

echo "== 5. 轮询终态，期望 SUCCESS =="
STATUS=""
for _ in $(seq 1 60); do
  STATUS="$(cli cmd status "$CMD_ID" | json_get status)"
  [[ "$STATUS" == "SUCCESS" || "$STATUS" == "FAILED" ]] && break
  sleep 0.1
done
[[ "$STATUS" == "SUCCESS" ]] || fail "终态为 $STATUS，期望 SUCCESS"
pass "指令终态 SUCCESS"

echo "== 6. 幂等回放：同 key 重复下发应返回同一 command_id =="
OUT2="$(cli cmd dispatch dev-smoke-1 reboot --idem smoke-key-1 --timeout-ms 3000)" || fail "重复下发失败"
CMD_ID2="$(printf '%s' "$OUT2" | json_get command_id)"
[[ "$CMD_ID2" == "$CMD_ID" ]] || fail "幂等失效：$CMD_ID2 != $CMD_ID"
pass "重复下发返回同一 command_id"

echo "== 7. 离线设备必须明确失败 =="
OUT3="$(cli cmd dispatch dev-offline-1 reboot --idem smoke-key-2)" || fail "离线下发未返回结果"
STATUS3="$(printf '%s' "$OUT3" | json_get status)"
ERR3="$(printf '%s' "$OUT3" | json_get error)"
[[ "$STATUS3" == "FAILED" && "$ERR3" == "DEVICE_OFFLINE" ]] || fail "离线设备语义错误: $OUT3"
pass "离线设备返回 FAILED/DEVICE_OFFLINE"

echo
echo "SMOKE PASSED"
