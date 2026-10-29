#!/usr/bin/env bash
# Download the local voice files that companion chat uses for spoken replies:
# the sherpa-onnx jars that :voice links when they are present, Kokoro 82M for
# Mac chat, the PocketTTS model, and the CC0 reference clip. Nothing it
# downloads is committed.
#
# The pinned versions and SHA-256 hashes live in the voice spike's setup.sh, so
# this script delegates to it rather than keep a second copy. That setup also
# fetches a Kotlin compiler, which it uses to resample the clip when neither
# ffmpeg nor sox is installed. Replace this with a Maven dependency once
# sherpa-onnx publishes one we can pin.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VOICE="$ROOT/spikes/ember-voice/mac"

bash "$VOICE/setup.sh"
bash "$ROOT/scripts/fetch-kokoro.sh"

cat <<EOF

Voice files are ready. Rebuild so Gradle links sherpa-onnx into :voice; the
launchers do this on their next run. Mac chat speaks with Kokoro:
  HOVEL_KOKORO_MODEL=$VOICE/models/kokoro-multi-lang-v1_0
Pocket stays available for a later cloned voice:
  HOVEL_VOICE_MODEL=$VOICE/models/sherpa-onnx-pocket-tts-int8-2026-01-26
  HOVEL_VOICE_REFERENCE=$VOICE/models/voices/0a67_enhanced-24k.wav
  HOVEL_VOICE_BACKEND=sherpa
EOF
