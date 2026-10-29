#!/bin/bash
# Play a clip through the voice-processing graph and score what the mic returns.
# A plain Gradle java process often has no microphone permission. The same test inside
# Gamero Adventures (Develop menu, or HOVEL_HEARING_SELF_TEST=1) uses the app's prompt.
# Stay quiet. The result is printed and written to ~/.gamero/hearing-self-check.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
if [[ "$(uname -s)" != Darwin ]]; then
  echo "The echo self-test needs the Mac microphone and scripts/build-ember-audio.sh." >&2
  exit 2
fi
# shellcheck disable=SC1091
source "$ROOT/scripts/dev-env.sh"
"$ROOT/scripts/build-ember-audio.sh"
export HOVEL_EMBER_AUDIO="${HOVEL_EMBER_AUDIO:-$ROOT/spikes/ember-voice/mac/deps/ember-audio/libember_audio.dylib}"
exec ./gradlew :voice:echoSelfTest --console=plain
