#!/usr/bin/env bash
# Run the composeApp tests on every target and print one count line per target.
#
# Usage: tools/test-all-targets.sh [target]...
#   jvm       :composeApp:jvmTest
#   android   :composeApp:testDebugUnitTest (needs the Android SDK)
#   ios       :composeApp:iosSimulatorArm64Test (Mac with Xcode only)
#   (none)    jvm and android, plus ios on a Mac
#
# The mobile targets exist only with -Pgamero.mobileTargets=true, which this
# script passes. It finds the Android SDK when ANDROID_HOME is unset, and sets
# DEVELOPER_DIR when xcode-select points to the Command Line Tools.
#
# Environment:
#   ANDROID_HOME        Android SDK (default: local.properties sdk.dir, then ~/Library/Android/sdk, ~/Android/Sdk)
#   BUILD_TIMINGS_FILE  CSV that every step's duration is appended to
#                       (default: .build-timings.csv at the repo root, git-ignored)
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

step() { echo "test-all-targets: $*"; }
fail() { echo "test-all-targets: $*" >&2; exit 1; }

started=$SECONDS
timings_file="${BUILD_TIMINGS_FILE:-$root/.build-timings.csv}"
case "$timings_file" in /*) ;; *) timings_file="$PWD/$timings_file" ;; esac
run_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
commit="$(git -C "$root" rev-parse --short HEAD 2>/dev/null || echo unknown)"
host="$(hostname -s 2>/dev/null || echo unknown)"
# Same columns as build-release.sh, so tools/build-timings.sh reads both.
record() {
  [[ -s "$timings_file" ]] || echo "run_id,finished_at,script,step,seconds,detail,commit,host" >"$timings_file"
  printf '%s,%s,test-all-targets,%s,%s,%s,%s,%s\n' "$run_id" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" "$2" "${3//,/;}" "$commit" "$host" >>"$timings_file"
}

targets=("$@")
if [[ ${#targets[@]} -eq 0 ]]; then
  targets=(jvm android)
  [[ "$(uname -s)" == Darwin ]] && targets+=(ios)
fi

task_for() {
  case "$1" in
    jvm) echo jvmTest ;;
    android) echo testDebugUnitTest ;;
    ios) echo iosSimulatorArm64Test ;;
    *) fail "unknown target '$1' (use jvm, android or ios)" ;;
  esac
}

for target in "${targets[@]}"; do task_for "$target" >/dev/null; done

if [[ " ${targets[*]} " == *" android "* && -z "${ANDROID_HOME:-}" ]]; then
  sdk="$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null || true)"
  for candidate in "$sdk" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [[ -n "$candidate" && -d "$candidate" ]]; then export ANDROID_HOME="$candidate"; break; fi
  done
  [[ -n "${ANDROID_HOME:-}" ]] || fail "Android SDK not found. Set ANDROID_HOME."
  step "ANDROID_HOME=$ANDROID_HOME"
fi

if [[ " ${targets[*]} " == *" ios "* ]]; then
  [[ "$(uname -s)" == Darwin ]] || fail "ios tests need a Mac with Xcode"
  if ! xcodebuild -version >/dev/null 2>&1 && [[ -d /Applications/Xcode.app ]]; then
    export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
    step "DEVELOPER_DIR=$DEVELOPER_DIR"
  fi
  xcodebuild -version >/dev/null 2>&1 || fail "Xcode not found. Install it, or set DEVELOPER_DIR."
fi

# Sums the JUnit XML results of one Gradle test task: "tests failures errors skipped".
count() {
  local dir="composeApp/build/test-results/$1"
  [[ -d "$dir" ]] || { echo "0 0 0 0"; return; }
  cat "$dir"/*.xml 2>/dev/null | grep -o '<testsuite [^>]*>' |
    awk '{
      for (i = 1; i <= NF; i++) {
        split($i, kv, "="); gsub(/"/, "", kv[2])
        if (kv[1] == "tests") t += kv[2]; else if (kv[1] == "failures") f += kv[2]
        else if (kv[1] == "errors") e += kv[2]; else if (kv[1] == "skipped") s += kv[2]
      }
    } END { printf "%d %d %d %d\n", t, f, e, s }'
}

summary=()
status=0
for target in "${targets[@]}"; do
  task="$(task_for "$target")"
  rm -rf "composeApp/build/test-results/$task"
  step "running :composeApp:$task"
  t=$SECONDS
  result=passed
  ./gradlew -Pgamero.mobileTargets=true ":composeApp:$task" --continue || { result=failed; status=1; }
  read -r tests failures errors skipped <<<"$(count "$task")"
  # A task that ran no tests proves nothing, so it does not count as a pass.
  if [[ "$result" == passed && "$tests" -eq 0 ]]; then result="no-tests"; status=1; fi
  took=$((SECONDS - t))
  line="$(printf '%-8s %-8s %4s tests, %s failures, %s errors, %s skipped (%ss)' \
    "$target" "$result" "$tests" "$failures" "$errors" "$skipped" "$took")"
  summary+=("$line")
  record "test-$target" "$took" "$result $tests tests $failures failures $errors errors"
done

record total "$((SECONDS - started))" "${targets[*]}"
echo
for line in "${summary[@]}"; do echo "$line"; done
exit "$status"
