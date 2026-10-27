#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
source "$ROOT/scripts/dev-env.sh"
PLATFORM="$(cd "$HOVEL_GAME_PROJECT/../.." && pwd)"
export HOVEL_PET_PROJECT="${HOVEL_PET_PROJECT:-$PLATFORM/ember-desktop-pet}"
export HOVEL_PET_GODOT="${HOVEL_PET_GODOT:-/Applications/Godot.app/Contents/MacOS/Godot}"
export GAMERO_CATALOG_PORT="${GAMERO_CATALOG_PORT:-8133}"
export GAMERO_WEB_URL="http://127.0.0.1:$GAMERO_CATALOG_PORT/"
: "${GAMERO_BROWSER_JAVA_HOME:?Set GAMERO_BROWSER_JAVA_HOME to your installed JBR with JCEF}"
[[ -d "$GAME_PLATFORM_CONTRACTS_ROOT" ]] || {
  echo "No event contracts at $GAME_PLATFORM_CONTRACTS_ROOT. Set GAME_PLATFORM_CONTRACTS_ROOT to an event-contracts checkout." >&2
  exit 1
}
warn_missing_voice
require_embed_godot
cd "$ROOT"
bash scripts/build-embedded-macos.sh
bash scripts/package-catalog-macos.sh
(cd "$PLATFORM/apps/game-bridge-host" && exec node --import tsx tools/companionLauncher.ts) &
WEB_PID=$!
trap 'kill "$WEB_PID" 2>/dev/null || true' EXIT
ready=0
for ((attempt=0; attempt<50; attempt++)); do
  kill -0 "$WEB_PID" 2>/dev/null || { echo 'Catalog server failed to start.' >&2; exit 1; }
  if curl --silent --fail "$GAMERO_WEB_URL" >/dev/null; then ready=1; break; fi
  sleep 0.2
done
[[ "$ready" == 1 ]] || { echo 'Catalog server did not become ready.' >&2; exit 1; }
"$ROOT/build/catalog-launcher/Gamero Adventures.app/Contents/MacOS/Gamero Adventures"
