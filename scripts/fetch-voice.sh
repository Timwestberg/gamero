#!/usr/bin/env bash
# Download the local voice files that companion chat uses for spoken replies:
# the sherpa-onnx jars that :voice links when they are present, Kokoro 82M for
# Mac chat, the PocketTTS model, the CC0 reference clip, and the on-device
# hearing models (Silero VAD v5, MIT, and the Apache-2.0 streaming Zipformer).
# Nothing it downloads is committed.
#
# The pinned versions and SHA-256 hashes for Kokoro and the jars live in the
# voice spike's setup.sh, so this script delegates to it rather than keep a
# second copy. Hearing hashes are pinned below. The Zipformer weights are the
# icefall LibriSpeech streaming export (Apache-2.0 code and weights; LibriSpeech
# is CC BY 4.0). Kroko and any CC-BY-NC file are refused.
# Replace the jar delegation with a Maven dependency once sherpa-onnx publishes one we can pin.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VOICE="$ROOT/spikes/ember-voice/mac"
# shellcheck source=../spikes/ember-voice/mac/script-lib.sh
. "$VOICE/script-lib.sh"

# Silero VAD v5 is MIT. The Zipformer archive's README says apache-2.0.
fetch_hearing() {
  local dest archive vad
  dest="$VOICE/models/hearing"
  vad="$dest/silero_vad.onnx"
  archive="$VOICE/models/sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2"
  download_hearing "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad_v5.onnx" \
    "$vad" "6b99cbfd39246b6706f98ec13c7c50c6b299181f2474fa05cbc8046acc274396"
  download_hearing "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2" \
    "$archive" "639e25b578e9e997131402199419c13a941f8e4e198e2da1ce57dbf5cf401282"
  local zipdir="$VOICE/models/sherpa-onnx-streaming-zipformer-en-2023-06-26"
  if [[ ! -f "$zipdir/tokens.txt" ]]; then
    tar -xjf "$archive" -C "$VOICE/models"
  fi
  if [[ -f "$zipdir/README.md" ]] && grep -Eiq 'non-commercial|cc-by-nc' "$zipdir/README.md"; then
    die "hearing model license is non-commercial"
  fi
  local encoder="$dest/zipformer/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
  local decoder="$dest/zipformer/decoder-epoch-99-avg-1-chunk-16-left-128.onnx"
  local joiner="$dest/zipformer/joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
  local tokens="$dest/zipformer/tokens.txt"
  mkdir -p "$dest/zipformer"
  install_hearing "$zipdir/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx" "$encoder" \
    "5022b2eca5b19d1bc104fcf33e26bc32604b7df553cd2e1f62e31dc7b05e9c87"
  install_hearing "$zipdir/decoder-epoch-99-avg-1-chunk-16-left-128.onnx" "$decoder" \
    "85914f840b71110487ece7a30ebd0bd4b8fb5c2623215bba3aa5ab4c138fc591"
  install_hearing "$zipdir/joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx" "$joiner" \
    "abd5e30f3f16fc510605c6029dba33f10e4386bd75c5bdc30cf94076864db10d"
  install_hearing "$zipdir/tokens.txt" "$tokens" \
    "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb"
  install_hearing "$zipdir/test_wavs/0.wav" "$dest/sample.wav" \
    "6bc58a4efdf20daac252b6b1502632601a71efe0308f6757dc1eda34891a7e4f"
}

download_hearing() {
  local url=$1 dest=$2 sha=$3
  if [[ -f "$dest" ]] && [[ "$(sha256_file "$dest")" == "$sha" ]]; then
    printf 'ok  %s\n' "$(basename "$dest")"
    return 0
  fi
  mkdir -p "$(dirname "$dest")"
  local tmp="${dest}.partial"
  rm -f "$tmp"
  printf 'get %s\n' "$(basename "$dest")"
  curl -fL --retry 5 --retry-delay 2 -o "$tmp" "$url"
  if [[ "$(sha256_file "$tmp")" != "$sha" ]]; then
    rm -f "$tmp"
    die "hash mismatch for $(basename "$dest")"
  fi
  mv "$tmp" "$dest"
}

install_hearing() {
  local src=$1 dest=$2 sha=$3
  if [[ ! -f "$src" ]]; then
    die "missing $src"
  fi
  if [[ "$(sha256_file "$src")" != "$sha" ]]; then
    die "hash mismatch for $(basename "$src")"
  fi
  if [[ -f "$dest" ]] && [[ "$(sha256_file "$dest")" == "$sha" ]]; then
    printf 'ok  %s\n' "$(basename "$dest")"
    return 0
  fi
  mkdir -p "$(dirname "$dest")"
  cp "$src" "$dest"
  printf 'ok  %s\n' "$(basename "$dest")"
}

bash "$VOICE/setup.sh"
bash "$ROOT/scripts/fetch-kokoro.sh"
fetch_hearing

cat <<EOF

Voice files are ready. Rebuild so Gradle links sherpa-onnx into :voice; the
launchers do this on their next run. Mac chat speaks with Kokoro:
  HOVEL_KOKORO_MODEL=$VOICE/models/kokoro-multi-lang-v1_0
Pocket stays available for a later cloned voice:
  HOVEL_VOICE_MODEL=$VOICE/models/sherpa-onnx-pocket-tts-int8-2026-01-26
  HOVEL_VOICE_REFERENCE=$VOICE/models/voices/0a67_enhanced-24k.wav
  HOVEL_VOICE_BACKEND=sherpa
Push-to-talk uses the hearing models:
  HOVEL_HEARING_MODEL=$VOICE/models/hearing
EOF
