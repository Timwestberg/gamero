#!/usr/bin/env bash
# One command for the Ember voice bench on a connected iPhone 14 Pro Max
# and an M-series iPad Pro.
#
#   cd voice/ios-bench
#   ./run.sh
#
# Optional:
#   SHERPA_ONNX_XCFRAMEWORK=/path/to/sherpa-onnx.xcframework
#   EMBER_VOICE_MODEL_DIR=/path/to/sherpa-onnx-pocket-tts-int8-2026-01-26
#   EMBER_VOICE_WAV=/path/to/0a67_enhanced-24k.wav
#   EMBER_VOICE_BACKEND=sherpa          # or fluid-audio
#   DEVELOPMENT_TEAM=XXXXXXXXXX
set -eu
HERE=$(CDPATH= cd "$(dirname "$0")" && pwd)
ROOT=$(CDPATH= cd "$HERE/../.." && pwd)

die() {
  printf 'error: %s\n' "$1" >&2
  exit 1
}

# Devices section only. Skip the Mac and anything under Simulators.
# Prints "name<TAB>udid" for an iPhone 14 Pro Max and an iPad Pro.
select_destinations() {
  awk '
    /^== Simulators ==/ { in_devices = 0 }
    /^== Devices ==/ { in_devices = 1; next }
    in_devices && /iPhone 14 Pro Max/ {
      if (match($0, /\([0-9A-Fa-f][0-9A-Fa-f-]+\)$/)) {
        udid = substr($0, RSTART + 1, RLENGTH - 2)
        name = substr($0, 1, RSTART - 1)
        gsub(/[ \t]+$/, "", name)
        print name "\t" udid
      }
    }
    in_devices && /iPad Pro/ && $0 !~ /Simulator/ {
      if (match($0, /\([0-9A-Fa-f][0-9A-Fa-f-]+\)$/)) {
        udid = substr($0, RSTART + 1, RLENGTH - 2)
        name = substr($0, 1, RSTART - 1)
        gsub(/[ \t]+$/, "", name)
        print name "\t" udid
      }
    }
  '
}

if [ "${1:-}" = "--self-check" ]; then
  sample=$(printf '%s\n' \
    '== Devices ==' \
    'MacBook Pro (AAAA)' \
    'iPhone 14 Pro Max (18.6) (00008110-AAA)' \
    'iPad Pro (M4) (18.6) (00008103-BBB)' \
    'iPhone 15 (18.6) (00008120-CCC)' \
    '== Simulators ==' \
    'iPhone 14 Pro Max (18.6) (11111111-DDD)' \
    'iPad Pro (M2) (18.6) (22222222-EEE)')
  got=$(printf '%s\n' "$sample" | select_destinations)
  phone=$(printf '%s\n' "$got" | awk -F '\t' '$1 ~ /iPhone 14 Pro Max/ { print $2 }')
  ipad=$(printf '%s\n' "$got" | awk -F '\t' '$1 ~ /iPad Pro/ { print $2 }')
  [ "$phone" = "00008110-AAA" ] || die "phone match got $phone"
  [ "$ipad" = "00008103-BBB" ] || die "ipad match got $ipad"
  printf '%s\n' "$got" | awk -F '\t' '$2 ~ /DDD|EEE|CCC/ { bad = 1 } END { exit bad }' \
    || die "matched a simulator or the wrong phone"
  echo "ios-bench self-check: ok"
  exit 0
fi

command -v xcodebuild >/dev/null 2>&1 || die "xcodebuild is not on this machine. Run this on the Mac that has the phones."
command -v xcrun >/dev/null 2>&1 || die "xcrun is not on this machine."

MODEL_DIR=${EMBER_VOICE_MODEL_DIR:-$ROOT/spikes/ember-voice/mac/models/sherpa-onnx-pocket-tts-int8-2026-01-26}
VOICE_WAV=${EMBER_VOICE_WAV:-$ROOT/spikes/ember-voice/mac/models/voices/0a67_enhanced-24k.wav}
BACKEND=${EMBER_VOICE_BACKEND:-sherpa}
THREADS=${EMBER_VOICE_THREADS:-2}
[ -d "$MODEL_DIR" ] || die "model dir not found: $MODEL_DIR"
[ -f "$VOICE_WAV" ] || die "voice wav not found: $VOICE_WAV"

XC=${SHERPA_ONNX_XCFRAMEWORK:-}
[ -n "$XC" ] || die "set SHERPA_ONNX_XCFRAMEWORK to the sherpa-onnx 2.0.0 iOS xcframework"
[ -d "$XC" ] || die "xcframework not found: $XC"
HEADER_DIR=$(find "$XC" -name 'c-api.h' -print | head -n 1 | xargs dirname 2>/dev/null || true)
[ -n "$HEADER_DIR" ] || die "c-api.h was not inside $XC"
FRAMEWORK_DIR=$(find "$XC" -type d -name 'sherpa-onnx.framework' -print | head -n 1 | xargs dirname 2>/dev/null || true)
[ -n "$FRAMEWORK_DIR" ] || die "sherpa-onnx.framework was not inside $XC"

python3 - "$HERE/EmberVoiceBench/bench-config.json" "$BACKEND" "$MODEL_DIR" "$VOICE_WAV" "$THREADS" <<'PY'
import json, sys
path, backend, model, wav, threads = sys.argv[1:]
with open(path, "w") as f:
    json.dump({
        "backend": backend,
        "model_dir": model,
        "voice_wav": wav,
        "threads": int(threads),
    }, f, indent=2)
    f.write("\n")
PY

devices=$(xcrun xctrace list devices 2>/dev/null || xcrun xcodebuild -showdestinations -project "$HERE/EmberVoiceBench.xcodeproj" -scheme EmberVoiceBench)
picked=$(printf '%s\n' "$devices" | select_destinations || true)
if [ -z "$picked" ]; then
  echo "No iPhone 14 Pro Max or iPad Pro in the device list."
  echo "Connect them, unlock them, and trust this Mac. Then run ./run.sh again."
  echo "The command for one device, once it is listed, is:"
  echo "  xcodebuild test -project voice/ios-bench/EmberVoiceBench.xcodeproj -scheme EmberVoiceBench -destination 'platform=iOS,name=iPhone 14 Pro Max' -allowProvisioningUpdates"
  exit 1
fi

mkdir -p "$HERE/results"
fail=0
while IFS="$(printf '\t')" read -r name udid; do
  [ -n "${udid:-}" ] || continue
  safe=$(printf '%s' "$name" | tr ' /' '__')
  echo "bench $name ($udid)"
  set +e
  if [ -n "${DEVELOPMENT_TEAM:-}" ]; then
    xcodebuild test \
      -project "$HERE/EmberVoiceBench.xcodeproj" \
      -scheme EmberVoiceBench \
      -destination "platform=iOS,id=$udid" \
      -allowProvisioningUpdates \
      -resultBundlePath "$HERE/results/$safe.xcresult" \
      "SHERPA_ONNX_HEADERS=$HEADER_DIR" \
      "SHERPA_ONNX_FRAMEWORKS=$FRAMEWORK_DIR" \
      "DEVELOPMENT_TEAM=$DEVELOPMENT_TEAM"
  else
    xcodebuild test \
      -project "$HERE/EmberVoiceBench.xcodeproj" \
      -scheme EmberVoiceBench \
      -destination "platform=iOS,id=$udid" \
      -allowProvisioningUpdates \
      -resultBundlePath "$HERE/results/$safe.xcresult" \
      "SHERPA_ONNX_HEADERS=$HEADER_DIR" \
      "SHERPA_ONNX_FRAMEWORKS=$FRAMEWORK_DIR"
  fi
  code=$?
  set -e
  if [ "$code" -ne 0 ]; then
    echo "xcodebuild failed for $name (exit $code)"
    fail=1
  fi
done <<EOF
$picked
EOF
exit "$fail"
