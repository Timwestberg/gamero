#!/bin/bash
# Shared settings for the macOS development launchers and packaging scripts.
# Source it from a bash script that already runs `set -euo pipefail`:
#
#   source "$ROOT/scripts/dev-env.sh"
#
# Sourcing only sets variables and defines functions; it prints nothing. Every
# exported default respects a value that is already set, except
# HOVEL_EMBED_LIBRARY, which is always the library these scripts build. The
# full list is in docs/companion-presentation/README.md, "Desktop companion
# settings".

GAMERO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Java 21 for Gradle, jpackage and the embed library's JNI headers.
if [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21)" || { echo 'Install JDK 21 or set JAVA_HOME.' >&2; return 1; }
fi
export JAVA_HOME

# Sibling checkouts, following game-platform's GAME_PLATFORM_SIBLING_ROOT
# convention: the folder that holds game-platform and server-platform beside
# this checkout. Point HOVEL_GAME_PROJECT or GAME_PLATFORM_CONTRACTS_ROOT at a
# worktree with another name.
SIBLING_ROOT="${GAME_PLATFORM_SIBLING_ROOT:-$(cd "$GAMERO_ROOT/.." && pwd)}"
export HOVEL_GAME_PROJECT="${HOVEL_GAME_PROJECT:-$SIBLING_ROOT/game-platform/games/ember-tower}"
export GAME_PLATFORM_CONTRACTS_ROOT="${GAME_PLATFORM_CONTRACTS_ROOT:-$SIBLING_ROOT/server-platform/event-contracts}"

# The native layer bridge that both embedded-game launchers build and load.
export HOVEL_EMBED_LIBRARY="$GAMERO_ROOT/build/embedded-godot/libhovel_embed.dylib"

# The pinned engine for the embedded game; Godot's embedding is still a beta.
EMBED_GODOT_VERSION='4.8.beta1.official.4d10419ca'

# Claude as a companion team runtime, on the player's own Claude Code sign-in. Development only:
# release builds ignore it until Anthropic confirms a companion persona is allowed.
export HOVEL_CLAUDE_PROVIDER="${HOVEL_CLAUDE_PROVIDER:-1}"

# Local voice for spoken chat replies. scripts/fetch-voice.sh downloads these.
VOICE_ROOT="$GAMERO_ROOT/spikes/ember-voice/mac"
export HOVEL_VOICE_MODEL="${HOVEL_VOICE_MODEL:-$VOICE_ROOT/models/sherpa-onnx-pocket-tts-int8-2026-01-26}"
export HOVEL_VOICE_REFERENCE="${HOVEL_VOICE_REFERENCE:-$VOICE_ROOT/models/voices/0a67_enhanced-24k.wav}"

# Stop unless the game project runs the pinned embedding engine.
require_embed_godot() {
  local found
  found="$("$HOVEL_GAME_PROJECT/scripts/godot.sh" --version)"
  if [[ "$found" != "$EMBED_GODOT_VERSION" ]]; then
    echo "The embedded prototype requires Godot 4.8-beta1 ($EMBED_GODOT_VERSION); found $found." >&2
    return 1
  fi
}

# Say up front when spoken replies will be off. Text chat still works.
warn_missing_voice() {
  if ! compgen -G "$VOICE_ROOT/deps/sherpa/sherpa-onnx-jvm-*.jar" >/dev/null \
    || [[ ! -d "$HOVEL_VOICE_MODEL" || ! -f "$HOVEL_VOICE_REFERENCE" ]]; then
    echo 'Spoken replies are off: voice files are missing. Run scripts/fetch-voice.sh to enable them.' >&2
  fi
}

# Fill JAVA_OPTIONS with jpackage `--java-options` pairs. Gradle owns the list
# (desktopAddOpens in composeApp/build.gradle.kts) and writes it beside the
# app inputs, so run a prepare*App task first.
load_desktop_java_options() {
  local option
  JAVA_OPTIONS=()
  while IFS= read -r option; do
    if [[ -n "$option" ]]; then JAVA_OPTIONS+=(--java-options "$option"); fi
  done < "$GAMERO_ROOT/composeApp/build/desktop-app/jvm-options.txt"
}
