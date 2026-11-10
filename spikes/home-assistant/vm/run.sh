#!/bin/sh
# Build the spike, sign it with the right entitlements, and boot Home Assistant OS.
# Usage: ./run.sh [--nat | --bridged <interface>] [--cpus N] [--memory-gb N]
#        ./run.sh interfaces
# SIGN_IDENTITY picks the signing identity; the default "-" signs ad hoc.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
cd "$here"
swift build -c release
bin="$here/.build/release/HaVmSpike"

entitlements="$here/nat.entitlements"
for arg in "$@"; do
  [ "$arg" = "--bridged" ] && entitlements="$here/bridged.entitlements"
done
codesign --force --sign "${SIGN_IDENTITY:--}" --entitlements "$entitlements" "$bin"

if [ "${1:-}" = "interfaces" ]; then exec "$bin" interfaces; fi
[ -f "$here/work/haos.img" ] || { echo "No image yet. Run ./setup.sh first." >&2; exit 66; }
exec "$bin" run --image "$here/work/haos.img" --state "$here/work/state" "$@"
