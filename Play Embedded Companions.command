#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
source "$ROOT/scripts/dev-env.sh"
require_embed_godot
bash "$ROOT/scripts/package-embedded-macos.sh"
cd "$ROOT"
APP="$(cat "$ROOT/build/embedded-godot/latest-app.txt")"
exec "$APP/Contents/MacOS/Hovel Embedded"
