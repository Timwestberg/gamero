#!/usr/bin/env bash
# Give the iPhone app Kokoro, the Mac's chat voice.
#
#   scripts/ios-voice.sh /path/to/sherpa-onnx/build-ios   # link the engine and bundle the model
#   scripts/ios-voice.sh --off                            # back to the system voice
#   scripts/ios-voice.sh --embed                          # called by the Xcode build; copies the model into the app
#
# The first form writes iosApp/Configuration/Voice.local.xcconfig (not committed), which the app's Config.xcconfig
# includes when it exists. It points the build at:
#   - sherpa-onnx 2.0.0 built for iOS: sherpa-onnx.xcframework and ios-onnxruntime/onnxruntime.xcframework, as
#     upstream's build-ios.sh makes them. The 2.0.0 release ships no iOS build.
#   - the Kokoro files scripts/fetch-voice.sh downloads for the Mac, with voices-blends.bin and lexicon-chat.txt,
#     which the Mac writes the first time Ember speaks.
# Without that file the app links no sherpa and speaks with the system voice.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG="$ROOT/iosApp/Configuration/Voice.local.xcconfig"
MODEL="${EMBER_KOKORO_MODEL_DIR:-$ROOT/spikes/ember-voice/mac/models/kokoro-multi-lang-v1_0}"
FILES="model.onnx voices-blends.bin tokens.txt lexicon-us-en.txt lexicon-chat.txt"

die() {
  echo "ios-voice: $*" >&2
  exit 1
}

if [ "${1:-}" = "--embed" ]; then
  # Xcode sets EMBER_KOKORO_MODEL_DIR from Voice.local.xcconfig. Unset, the app carries no model.
  [ -n "${EMBER_KOKORO_MODEL_DIR:-}" ] || exit 0
  dest="${TARGET_BUILD_DIR:?}/${UNLOCALIZED_RESOURCES_FOLDER_PATH:?}/kokoro"
  mkdir -p "$dest"
  for file in $FILES; do
    [ -f "$EMBER_KOKORO_MODEL_DIR/$file" ] || die "missing $EMBER_KOKORO_MODEL_DIR/$file"
    # A clone where the disk allows it: the model is 330 MB and the copy is made at every build.
    if [ ! -f "$dest/$file" ] || [ "$EMBER_KOKORO_MODEL_DIR/$file" -nt "$dest/$file" ]; then
      cp -c "$EMBER_KOKORO_MODEL_DIR/$file" "$dest/$file" 2>/dev/null || cp "$EMBER_KOKORO_MODEL_DIR/$file" "$dest/$file"
    fi
  done
  exit 0
fi

if [ "${1:-}" = "--off" ]; then
  rm -f "$CONFIG"
  echo "ios-voice: Kokoro is off; the next build speaks with the system voice."
  exit 0
fi

SHERPA="${1:-${SHERPA_ONNX_IOS:-}}"
[ -n "$SHERPA" ] || die "give the sherpa-onnx build-ios directory, or --off"
SHERPA="$(cd "$SHERPA" && pwd)"
for slice in ios-arm64 ios-arm64_x86_64-simulator; do
  [ -f "$SHERPA/sherpa-onnx.xcframework/$slice/SherpaOnnxC.framework/Headers/sherpa-onnx/c-api/c-api.h" ] \
    || die "no sherpa-onnx.xcframework ($slice) in $SHERPA"
  [ -d "$SHERPA/ios-onnxruntime/onnxruntime.xcframework/$slice/onnxruntime.framework" ] \
    || die "no ios-onnxruntime/onnxruntime.xcframework ($slice) in $SHERPA"
done
for file in $FILES; do
  [ -f "$MODEL/$file" ] || die "missing $MODEL/$file. Run scripts/fetch-voice.sh, then let Ember speak once on the Mac."
done

cat > "$CONFIG" <<XCCONFIG
// Written by scripts/ios-voice.sh. Not committed: the paths are this machine's.
EMBER_VOICE_SHERPA_DIR = $SHERPA
EMBER_KOKORO_MODEL_DIR = $MODEL
EMBER_VOICE_SLICE_iphoneos = ios-arm64
EMBER_VOICE_SLICE_iphonesimulator = ios-arm64_x86_64-simulator
EMBER_VOICE_SLICE = \$(EMBER_VOICE_SLICE_\$(PLATFORM_NAME))
EMBER_VOICE_SHERPA_SLICE = \$(EMBER_VOICE_SHERPA_DIR)/sherpa-onnx.xcframework/\$(EMBER_VOICE_SLICE)
EMBER_VOICE_ORT_SLICE = \$(EMBER_VOICE_SHERPA_DIR)/ios-onnxruntime/onnxruntime.xcframework/\$(EMBER_VOICE_SLICE)
HEADER_SEARCH_PATHS = \$(inherited) "\$(EMBER_VOICE_SHERPA_SLICE)/SherpaOnnxC.framework/Headers/sherpa-onnx/c-api"
FRAMEWORK_SEARCH_PATHS = \$(inherited) "\$(EMBER_VOICE_SHERPA_SLICE)" "\$(EMBER_VOICE_ORT_SLICE)"
OTHER_LDFLAGS = \$(inherited) -framework SherpaOnnxC -framework onnxruntime
XCCONFIG
echo "ios-voice: wrote $CONFIG"
echo "ios-voice: the next build links Kokoro and bundles $(du -sh "$MODEL/model.onnx" | cut -f1) of model."
