#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
export HOVEL_GAME_PROJECT="${HOVEL_GAME_PROJECT:-$ROOT/../game-platform-companions/games/ember-tower}"
PLATFORM="$(cd "$HOVEL_GAME_PROJECT/../.." && pwd)"
export HOVEL_PET_PROJECT="${HOVEL_PET_PROJECT:-$PLATFORM/ember-desktop-pet}"
export HOVEL_PET_GODOT="${HOVEL_PET_GODOT:-/Applications/Godot.app/Contents/MacOS/Godot}"
export HOVEL_VOICE_MODEL="${HOVEL_VOICE_MODEL:-$ROOT/spikes/ember-voice/mac/models/sherpa-onnx-pocket-tts-int8-2026-01-26}"
export HOVEL_VOICE_REFERENCE="${HOVEL_VOICE_REFERENCE:-$ROOT/spikes/ember-voice/mac/models/voices/0a67_enhanced-24k.wav}"
if ! compgen -G "$ROOT/spikes/ember-voice/mac/deps/sherpa/sherpa-onnx-jvm-*.jar" >/dev/null \
  || [[ ! -d "$HOVEL_VOICE_MODEL" || ! -f "$HOVEL_VOICE_REFERENCE" ]]; then
  echo 'Spoken replies are off: voice files are missing. Run scripts/fetch-voice.sh to enable them.' >&2
fi
if [[ -d "$ROOT/../server-platform-companion-chat/event-contracts" ]]; then
  export GAME_PLATFORM_CONTRACTS_ROOT="${GAME_PLATFORM_CONTRACTS_ROOT:-$ROOT/../server-platform-companion-chat/event-contracts}"
fi
export HOVEL_EMBED_LIBRARY="$ROOT/build/embedded-godot/libhovel_embed.dylib"
export GAMERO_CATALOG_PORT="${GAMERO_CATALOG_PORT:-8133}"
export GAMERO_WEB_URL="http://127.0.0.1:$GAMERO_CATALOG_PORT/"
: "${GAMERO_BROWSER_JAVA_HOME:?Set GAMERO_BROWSER_JAVA_HOME to your installed JBR with JCEF}"
if [[ "$("$HOVEL_GAME_PROJECT/scripts/godot.sh" --version)" != "4.8.beta1.official.4d10419ca" ]]; then
  echo 'The embedded prototype requires Godot 4.8-beta1 (4d10419ca).' >&2
  exit 1
fi
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
