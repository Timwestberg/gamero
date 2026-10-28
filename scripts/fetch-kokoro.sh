#!/usr/bin/env bash
# Download Kokoro 82M v1.0 and build the four chat blends into voices-blends.bin.
# The archive, the four files chat loads, and the derived voice table are hash
# pinned. Nothing this downloads is committed. The model and the voice table
# are Apache-2.0. Expresso and test_wavs stay refused elsewhere.
#
# sherpa-onnx 2.0.0 does not use the espeak-ng data in this archive. Chat loads
# model.onnx, voices-blends.bin, tokens.txt, and lexicon-us-en.txt.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MAC="$ROOT/spikes/ember-voice/mac"
# shellcheck source=../spikes/ember-voice/mac/script-lib.sh
. "$MAC/script-lib.sh"

ARCHIVE="kokoro-multi-lang-v1_0.tar.bz2"
ARCHIVE_SHA="60703707bc05ccd48d60e1eb723f38b07d6e474795a0b6a588d7220400e0f7a4"
ARCHIVE_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${ARCHIVE}"
MODEL_SHA="b40f62b166ac8164b0627ef48a0b358eda0985e272fb03ef5252e7206305da11"
VOICES_SHA="1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29"
TOKENS_SHA="6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425"
LEXICON_SHA="7daaab53a181be9885b853a8582bf1838186317e5dadacbcef9c426d6fa0da14"

DEST="$MAC/models/kokoro-multi-lang-v1_0"
ARCHIVE_PATH="$MAC/models/$ARCHIVE"

if ! command -v curl >/dev/null 2>&1; then
  die "curl is required"
fi
if ! command -v tar >/dev/null 2>&1; then
  die "tar is required"
fi
if ! command -v python3 >/dev/null 2>&1; then
  die "python3 is required to build the Kokoro blends"
fi

hash_ok() {
  file=$1
  sha=$2
  if [ ! -f "$file" ]; then
    return 1
  fi
  got=$(sha256_file "$file")
  [ "$got" = "$sha" ]
}

download_archive() {
  mkdir -p "$MAC/models"
  tmp="${ARCHIVE_PATH}.partial"
  rm -f "$tmp"
  printf 'get %s\n' "$ARCHIVE"
  curl -fL --retry 5 --retry-delay 2 --progress-bar -o "$tmp" "$ARCHIVE_URL"
  got=$(sha256_file "$tmp")
  if [ "$got" != "$ARCHIVE_SHA" ]; then
    rm -f "$tmp"
    die "hash mismatch for $ARCHIVE"
  fi
  mv "$tmp" "$ARCHIVE_PATH"
}

files_ok() {
  hash_ok "$DEST/model.onnx" "$MODEL_SHA" \
    && hash_ok "$DEST/voices.bin" "$VOICES_SHA" \
    && hash_ok "$DEST/tokens.txt" "$TOKENS_SHA" \
    && hash_ok "$DEST/lexicon-us-en.txt" "$LEXICON_SHA"
}

if files_ok; then
  printf 'ok  kokoro-multi-lang-v1_0\n'
else
  if ! hash_ok "$ARCHIVE_PATH" "$ARCHIVE_SHA"; then
    rm -f "$ARCHIVE_PATH"
    download_archive
  else
    printf 'ok  %s\n' "$ARCHIVE"
  fi
  tar -xjf "$ARCHIVE_PATH" -C "$MAC/models"
  if ! files_ok; then
    die "extracted Kokoro files do not match the pinned hashes"
  fi
fi

python3 "$ROOT/voice/kokoro/pack-blends.py" \
  --upstream "$DEST/voices.bin" \
  --output "$DEST/voices-blends.bin"

cat <<EOF

Kokoro is ready for Mac chat:
  HOVEL_KOKORO_MODEL=$DEST
The voice table is voices-blends.bin. Upstream voices.bin is unchanged.
Pick a voice with HOVEL_KOKORO_VOICE or ~/.gamero/kokoro-voice.
Ids: adam70-michael30 (Sam, default), adam60-george40 (Jamie),
adam55-bella45 (Riley), heart60-adam40 (Harper).
EOF
