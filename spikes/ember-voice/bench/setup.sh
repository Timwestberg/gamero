#!/usr/bin/env bash
# Download the offline ASR model used for WER, and make sure the PocketTTS
# spike dependencies are present. Weights stay gitignored.
set -eu

ROOT=$(cd "$(dirname "$0")" && pwd)
cd "$ROOT"
. "$ROOT/../mac/script-lib.sh"

ASR_ARCHIVE=sherpa-onnx-whisper-tiny.en.tar.bz2
ASR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/${ASR_ARCHIVE}"
ASR_SHA=2bd6cf965c8bb3e068ef9fa2191387ee63a9dfa2a4e37582a8109641c20005dd
ASR_DIR="$ROOT/models/sherpa-onnx-whisper-tiny.en"

if [ ! -x "$ROOT/../mac/setup.sh" ]; then
  die "missing ../mac/setup.sh. This bench builds on the PocketTTS spike."
fi
"$ROOT/../mac/setup.sh"

mkdir -p "$ROOT/models"
if [ -f "$ASR_DIR/tiny.en-encoder.int8.onnx" ] && [ -f "$ASR_DIR/tiny.en-decoder.int8.onnx" ] && [ -f "$ASR_DIR/tiny.en-tokens.txt" ]; then
  printf 'ok  asr %s\n' "sherpa-onnx-whisper-tiny.en"
else
  archive="$ROOT/models/$ASR_ARCHIVE"
  tmp="${archive}.partial"
  rm -f "$tmp"
  printf 'get %s\n' "$ASR_ARCHIVE"
  curl -fL --retry 5 --retry-delay 2 -o "$tmp" "$ASR_URL"
  got=$(sha256_file "$tmp")
  if [ "$got" != "$ASR_SHA" ]; then
    die "ASR archive hash mismatch (got $got)"
  fi
  mv "$tmp" "$archive"
  tar -xjf "$archive" -C "$ROOT/models"
  rm -f "$archive"
  printf 'ok  asr %s\n' "sherpa-onnx-whisper-tiny.en"
fi

printf '\nbench setup done.\nnext: ./run.sh --self-check && ./run.sh\n'
