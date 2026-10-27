#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
export HOVEL_GAME_PROJECT="${HOVEL_GAME_PROJECT:-$ROOT/../game-platform-companions/games/ember-tower}"
export HOVEL_EMBED_LIBRARY="$ROOT/build/embedded-godot/libhovel_embed.dylib"
VERSION="$("$HOVEL_GAME_PROJECT/scripts/godot.sh" --version)"
if [[ "$VERSION" != "4.8.beta1.official.4d10419ca" ]]; then
  echo "This native embedding prototype requires Godot 4.8-beta1 (4d10419ca); found $VERSION" >&2
  exit 1
fi
bash "$ROOT/scripts/package-embedded-macos.sh"
cd "$ROOT"
APP="$(cat "$ROOT/build/embedded-godot/latest-app.txt")"
exec "$APP/Contents/MacOS/Hovel Embedded"
