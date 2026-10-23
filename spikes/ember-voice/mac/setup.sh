#!/usr/bin/env bash
# Download the PocketTTS int8 model, a CC0 voice clip, sherpa-onnx jars, and
# the Kotlin compiler. Nothing here is committed.
set -eu

ROOT=$(cd "$(dirname "$0")" && pwd)
cd "$ROOT"
. "$ROOT/script-lib.sh"

CONF="$ROOT/config/harness.conf"
if [ ! -f "$CONF" ]; then
  die "missing $CONF"
fi

SHERPA_VERSION=2.0.0
JVM_JAR_SHA=ae9dea81af11253c874bed3a7a35e610b583f0350e8380786a1900eff8a831f0
MODEL_ARCHIVE=sherpa-onnx-pocket-tts-int8-2026-01-26.tar.bz2
MODEL_SHA=2f3b88823cbbb9bf0b2477ec8ae7b3fec417b3a87b6bb5f256dba66f2ad967cb
MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${MODEL_ARCHIVE}"
DEFAULT_MODEL_DIR="models/sherpa-onnx-pocket-tts-int8-2026-01-26"
DEFAULT_VOICE_URL="https://huggingface.co/kyutai/tts-voices/resolve/main/voice-donations/0a67_enhanced.wav"
DEFAULT_VOICE_SHA=f3d11f89eb6ee77ca0ab1ceed734755d0fff3d2791050cb4fe20428e0f6a76b3
KOTLIN_VERSION=2.4.21
KOTLIN_ZIP_SHA=7cc140e76daf416a0424a5557f99afd7ca89ebd463ea1101261e3e01c33e75cd
KOTLIN_URL="https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN_VERSION}/kotlin-compiler-${KOTLIN_VERSION}.zip"

# Native jar hashes, keyed by sherpa's os-arch id. Bash 3.2 has no
# associative arrays, so this is a case statement.
native_sha() {
  case "$1" in
    linux-aarch64) printf '%s\n' "d21bac2e5d8d6695e3c5e24a60acabafafca91d0d33bbcd112f5cac5b2c5a78d" ;;
    linux-x64) printf '%s\n' "fff92515b4cd6aade0aa2110026dffcaaa0e1814fcbef243cfed01fe61764ac0" ;;
    osx-aarch64) printf '%s\n' "cf88aab66f011c155f462674d8ec6dd6bf4dbc0fb508306f907068adff91b196" ;;
    osx-x64) printf '%s\n' "439e55292c1d1d4f317c0dae17a21be284ac96b016dd700293cf35dd46b00a02" ;;
    *) die "no pinned native jar for $1" ;;
  esac
}

require_java
if ! command -v curl >/dev/null 2>&1; then
  die "curl is required"
fi
if ! command -v tar >/dev/null 2>&1; then
  die "tar is required (macOS includes it)"
fi

conf_version=$(conf_get sherpa_version "$CONF")
if [ -n "$conf_version" ] && [ "$conf_version" != "$SHERPA_VERSION" ]; then
  die "config sherpa_version is $conf_version but setup.sh pins $SHERPA_VERSION"
fi

model_dir=$(conf_get model_dir "$CONF")
voice=$(conf_get voice "$CONF")
voice_url=$(conf_get voice_url "$CONF")
if [ -z "$model_dir" ] || [ -z "$voice" ] || [ -z "$voice_url" ]; then
  die "config needs model_dir, voice, and voice_url"
fi

plat=$(platform_id)
cpu=$(arch_id)
native_id="${plat}-${cpu}"
native_name="sherpa-onnx-native-lib-${native_id}-${SHERPA_VERSION}.jar"

download_verified() {
  url=$1
  dest=$2
  sha=$3
  if [ -f "$dest" ]; then
    got=$(sha256_file "$dest")
    if [ "$got" = "$sha" ]; then
      printf 'ok  %s\n' "$(basename "$dest")"
      return 0
    fi
    printf 'replacing %s (hash mismatch)\n' "$(basename "$dest")"
    rm -f "$dest"
  fi
  mkdir -p "$(dirname "$dest")"
  tmp="${dest}.partial"
  rm -f "$tmp"
  printf 'get %s\n' "$(basename "$dest")"
  curl -fL --retry 5 --retry-delay 2 --progress-bar -o "$tmp" "$url"
  got=$(sha256_file "$tmp")
  if [ "$got" != "$sha" ]; then
    rm -f "$tmp"
    die "hash mismatch for $(basename "$dest")"
  fi
  mv "$tmp" "$dest"
}

extract_zip() {
  zip=$1
  dest=$2
  mkdir -p "$dest"
  if command -v unzip >/dev/null 2>&1; then
    unzip -q -o "$zip" -d "$dest"
  elif command -v jar >/dev/null 2>&1; then
    (cd "$dest" && jar xf "$zip")
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c 'import sys, zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$zip" "$dest"
  else
    die "need unzip, jar, or python3 to unpack the Kotlin compiler"
  fi
}

mkdir -p "$ROOT/deps/sherpa" "$ROOT/deps/kotlin" "$ROOT/models" "$ROOT/build"

download_verified \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-jvm-${SHERPA_VERSION}.jar" \
  "$ROOT/deps/sherpa/sherpa-onnx-jvm-${SHERPA_VERSION}.jar" \
  "$JVM_JAR_SHA"

download_verified \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/${native_name}" \
  "$ROOT/deps/sherpa/${native_name}" \
  "$(native_sha "$native_id")"

kotlin_home="$ROOT/deps/kotlin/kotlinc"
if [ ! -x "$kotlin_home/bin/kotlinc" ]; then
  download_verified \
    "$KOTLIN_URL" \
    "$ROOT/deps/kotlin/kotlin-compiler-${KOTLIN_VERSION}.zip" \
    "$KOTLIN_ZIP_SHA"
  extract_zip "$ROOT/deps/kotlin/kotlin-compiler-${KOTLIN_VERSION}.zip" "$ROOT/deps/kotlin"
  chmod +x "$kotlin_home/bin/kotlinc" "$kotlin_home/bin/kotlinc-jvm" 2>/dev/null || true
fi
if [ ! -x "$kotlin_home/bin/kotlinc" ]; then
  die "Kotlin compiler did not unpack to $kotlin_home"
fi

printf 'compiling harness\n'
mkdir -p "$ROOT/build/classes"
"$kotlin_home/bin/kotlinc" \
  -jvm-target 17 \
  -cp "$ROOT/deps/sherpa/sherpa-onnx-jvm-${SHERPA_VERSION}.jar" \
  -d "$ROOT/build/classes" \
  "$ROOT/src/EmberVoiceSpike.kt"

marker="$ROOT/$model_dir/lm_main.int8.onnx"
if [ -f "$marker" ]; then
  printf 'ok  model %s\n' "$model_dir"
else
  if [ "$model_dir" != "$DEFAULT_MODEL_DIR" ]; then
    die "model files are missing in $model_dir. Restore model_dir to $DEFAULT_MODEL_DIR and rerun ./setup.sh, or unpack a sherpa-onnx PocketTTS archive there yourself."
  fi
  download_verified "$MODEL_URL" "$ROOT/models/$MODEL_ARCHIVE" "$MODEL_SHA"
  printf 'unpacking model\n'
  tar -xjf "$ROOT/models/$MODEL_ARCHIVE" -C "$ROOT/models"
  rm -f "$ROOT/models/$MODEL_ARCHIVE"
  if [ ! -f "$marker" ]; then
    die "model archive did not contain $marker"
  fi
fi

voice_path="$ROOT/$voice"
if [ "$voice_url" = "$DEFAULT_VOICE_URL" ]; then
  source_voice="$ROOT/models/voices/0a67_enhanced.source.wav"
  legacy_voice="$ROOT/models/voices/0a67_enhanced.wav"
  mkdir -p "$(dirname "$source_voice")"
  if [ ! -f "$source_voice" ] && [ -f "$legacy_voice" ]; then
    got=$(sha256_file "$legacy_voice")
    if [ "$got" = "$DEFAULT_VOICE_SHA" ]; then
      cp "$legacy_voice" "$source_voice"
    fi
  fi
  if [ -f "$source_voice" ]; then
    got=$(sha256_file "$source_voice")
    if [ "$got" != "$DEFAULT_VOICE_SHA" ]; then
      printf 'replacing voice source (hash mismatch)\n'
      rm -f "$source_voice"
    fi
  fi
  if [ ! -f "$source_voice" ]; then
    tmp="${source_voice}.partial"
    rm -f "$tmp"
    printf 'get voice %s\n' "$(basename "$source_voice")"
    curl -fL --retry 5 --retry-delay 2 --progress-bar -o "$tmp" "$voice_url"
    got=$(sha256_file "$tmp")
    if [ "$got" != "$DEFAULT_VOICE_SHA" ]; then
      rm -f "$tmp"
      die "voice hash mismatch for the default CC0 clip"
    fi
    mv "$tmp" "$source_voice"
  else
    printf 'ok  voice source %s\n' "$(basename "$source_voice")"
  fi
  source_sha=$(sha256_file "$source_voice")
  stamp="${voice_path}.source-sha"
  have_stamp=""
  if [ -f "$stamp" ]; then
    have_stamp=$(cat "$stamp")
  fi
  if [ -f "$voice_path" ] && [ "$have_stamp" = "$source_sha" ]; then
    printf 'ok  voice %s (24 kHz)\n' "$voice"
  else
    printf 'converting voice to 24 kHz\n'
    mkdir -p "$(dirname "$voice_path")"
    # Keep a .wav suffix. ffmpeg refuses to mux when the name ends in .partial.
    tmp="${voice_path}.tmp.wav"
    rm -f "$tmp"
    if command -v ffmpeg >/dev/null 2>&1; then
      ffmpeg -y -loglevel error -f wav -i "$source_voice" -ac 1 -ar 24000 -c:a pcm_s16le -f wav "$tmp"
    elif command -v sox >/dev/null 2>&1; then
      sox "$source_voice" -c 1 -r 24000 -b 16 -e signed-integer "$tmp"
    else
      java -cp "$ROOT/build/classes:$ROOT/deps/kotlin/kotlinc/lib/kotlin-stdlib.jar:$ROOT/deps/sherpa/sherpa-onnx-jvm-${SHERPA_VERSION}.jar" \
        EmberVoiceSpikeKt --resample "$source_voice" "$tmp"
    fi
    mv "$tmp" "$voice_path"
    printf '%s\n' "$source_sha" > "$stamp"
  fi
else
  if [ -f "$voice_path" ]; then
    printf 'ok  voice %s\n' "$voice"
  else
    mkdir -p "$(dirname "$voice_path")"
    tmp="${voice_path}.partial"
    rm -f "$tmp"
    printf 'get voice %s\n' "$(basename "$voice_path")"
    curl -fL --retry 5 --retry-delay 2 --progress-bar -o "$tmp" "$voice_url"
    mv "$tmp" "$voice_path"
    printf 'note: custom voice_url is saved as downloaded. PocketTTS resamples it on every call unless it is already 24 kHz mono PCM.\n'
  fi
fi

printf '\nsetup done (%s-%s, Java %s, sherpa-onnx %s).\n' "$plat" "$cpu" "$(java_major)" "$SHERPA_VERSION"
printf 'next: ./run.sh\n'
