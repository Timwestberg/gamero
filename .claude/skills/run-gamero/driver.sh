#!/bin/bash
# Launch and drive the Gamero desktop catalog app (Hovel companions) on macOS.
# Usage: driver.sh preflight | start | status | page <out.png> [click text ...] | logs [lines] | stop
# Run from anywhere; paths resolve from this file. Logs: build/run-logs/catalog.log.
set -euo pipefail

SKILL="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SKILL/../../.." && pwd)"
SIBLINGS="$(cd "$ROOT/.." && pwd)"
LOG_DIR="$ROOT/build/run-logs"
LOG="$LOG_DIR/catalog.log"
PID_FILE="$LOG_DIR/launcher.pid"
APP_MATCH='catalog-launcher/Gamero Adventures.app/Contents/MacOS/Gamero Adventures'
URL="http://127.0.0.1:${GAMERO_CATALOG_PORT:-8133}/"

# The JetBrains Runtime with JCEF that the app's browser needs.
jbr_home() {
  if [[ -n "${GAMERO_BROWSER_JAVA_HOME:-}" ]]; then echo "$GAMERO_BROWSER_JAVA_HOME"; return; fi
  local candidate
  for candidate in "$HOME"/Library/Java/JavaVirtualMachines/jbrsdk_jcef-*/Contents/Home; do
    [[ -d "$candidate" ]] && { echo "$candidate"; return; }
  done
  return 1
}

check() { if eval "$2" >/dev/null 2>&1; then echo "ok    $1"; else echo "MISS  $1${3:+ — $3}"; FAILED=1; fi; }

preflight() {
  FAILED=0
  check "JDK 21" "/usr/libexec/java_home -v 21"
  check "JBR with JCEF" "jbr_home" "install jbrsdk_jcef 21 or set GAMERO_BROWSER_JAVA_HOME"
  check "game-platform checkout" "test -d '$SIBLINGS/game-platform/games/ember-tower'"
  check "game-platform node_modules" "test -d '$SIBLINGS/game-platform/apps/game-bridge-host/node_modules'" "cd ../game-platform && pnpm install"
  check "server-platform contracts dist" "test -d '$SIBLINGS/server-platform/event-contracts/dist'" "cd ../server-platform/event-contracts && pnpm build, then pnpm install in game-platform"
  check "Godot 4.8-beta1 (embedded game)" "test -x '$HOME/Applications/godot-4.8-beta1/Godot.app/Contents/MacOS/Godot'"
  check "Godot 4.7 (desktop pet)" "test -x /Applications/Godot.app/Contents/MacOS/Godot"
  check "voice files (optional)" "ls '$ROOT'/spikes/ember-voice/mac/deps/sherpa/sherpa-onnx-jvm-*.jar" "scripts/fetch-voice.sh"
  check "Claude Code signed in (optional)" "claude auth status --json" "claude auth login"
  return "$FAILED"
}

app_pid() { pgrep -f "$APP_MATCH" | head -1; }
http_code() { curl -s --max-time 3 -o /dev/null -w '%{http_code}' "$URL" || true; }

status() {
  local pid; pid="$(app_pid || true)"
  if [[ -n "$pid" ]]; then echo "app:     running (pid $pid)"; else echo 'app:     not running'; fi
  echo "catalog: $URL -> $(http_code)"
}

start() {
  if [[ -n "$(app_pid || true)" ]]; then echo 'Already running.'; status; return 0; fi
  local jbr; jbr="$(jbr_home)" || { echo 'No JBR with JCEF found; run preflight.' >&2; return 1; }
  mkdir -p "$LOG_DIR"
  # A plain backgrounded command, not `cd && cmd &`: bash would fork a subshell for the list that
  # keeps the caller's stdout open while the app runs, so `driver.sh start | tail` never ends.
  (
    cd "$ROOT"
    GAMERO_BROWSER_JAVA_HOME="$jbr" nohup ./'Play From Catalog.command' >"$LOG" 2>&1 </dev/null &
    echo $! >"$PID_FILE"
  )
  echo "Building and launching (first run takes a few minutes); log: $LOG"
  local waited=0
  until [[ "$(http_code)" == 200 && -n "$(app_pid || true)" ]]; do
    if ! kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then echo 'Launcher exited:' >&2; tail -20 "$LOG" >&2; return 1; fi
    (( waited >= 600 )) && { echo 'Timed out after 10 minutes.' >&2; tail -20 "$LOG" >&2; return 1; }
    sleep 2; waited=$((waited + 2))
  done
  status
}

page() {
  [[ $# -ge 1 ]] || { echo 'usage: driver.sh page <out.png> [click text ...]' >&2; return 2; }
  node "$SKILL/page.mjs" "$URL" "$@"
}

stop() {
  local pid; pid="$(app_pid || true)"
  [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
  [[ -f "$PID_FILE" ]] && kill "$(cat "$PID_FILE")" 2>/dev/null || true
  local waited=0
  while [[ -n "$(app_pid || true)" || "$(http_code)" == 200 ]] && (( waited < 30 )); do sleep 1; waited=$((waited + 1)); done
  pkill -f 'tools/companionLauncher.ts' 2>/dev/null || true
  rm -f "$PID_FILE"
  status
}

case "${1:-}" in
  preflight) preflight ;;
  start) start ;;
  status) status ;;
  page) shift; page "$@" ;;
  logs) tail -n "${2:-40}" "$LOG" ;;
  stop) stop ;;
  *) echo 'usage: driver.sh preflight | start | status | page <out.png> [click text ...] | logs [lines] | stop' >&2; exit 2 ;;
esac
