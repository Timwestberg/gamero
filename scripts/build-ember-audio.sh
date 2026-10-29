#!/bin/bash
# Build the Mac voice-processing helper (arm64). Ad-hoc signed so the JVM can load it.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ "$(uname -s)" != Darwin ]]; then
  echo "build-ember-audio.sh runs on macOS. The Linux tests cover the duplex policy without this library." >&2
  exit 1
fi
if [[ "$(uname -m)" != arm64 ]]; then
  echo "This helper is built for Apple silicon (arm64)." >&2
  exit 1
fi
: "${JAVA_HOME:?Set JAVA_HOME to the JBR 21 used by the catalog}"
OUT="$ROOT/spikes/ember-voice/mac/deps/ember-audio"
mkdir -p "$OUT"
clang -dynamiclib -arch arm64 -fobjc-arc \
  -framework Foundation -framework AppKit -framework AVFoundation -framework CoreAudio -framework AudioToolbox \
  -I "$JAVA_HOME/include" -I "$JAVA_HOME/include/darwin" \
  -o "$OUT/libember_audio.dylib" \
  "$ROOT/voice/native/ember_audio.m" \
  "$ROOT/voice/native/ember_audio_jni.c"
codesign -s - --force "$OUT/libember_audio.dylib"
echo "signed $OUT/libember_audio.dylib"
