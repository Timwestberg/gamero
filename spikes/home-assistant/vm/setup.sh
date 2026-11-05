#!/bin/sh
# Download the official Home Assistant OS generic-aarch64 disk image into work/ and grow it to 32 GB.
# Usage: ./setup.sh [version]   (default: the latest release)
set -eu

here=$(cd "$(dirname "$0")" && pwd)
work="$here/work"
mkdir -p "$work"

command -v xz >/dev/null || { echo "xz is missing. Install it with: brew install xz" >&2; exit 69; }

release_api="https://api.github.com/repos/home-assistant/operating-system/releases"
if [ $# -ge 1 ]; then release_url="$release_api/tags/$1"; else release_url="$release_api/latest"; fi

# Pick the raw generic-aarch64 image from the release; print its URL, name, version and SHA-256 (when GitHub has one).
set -- $(curl -fsSL "$release_url" | python3 -c '
import json, sys
release = json.load(sys.stdin)
version = release["tag_name"]
name = f"haos_generic-aarch64-{version}.img.xz"
for asset in release["assets"]:
    if asset["name"] == name:
        digest = (asset.get("digest") or "").removeprefix("sha256:") or "none"
        print(asset["browser_download_url"], name, version, digest)
        sys.exit(0)
others = [a["name"] for a in release["assets"] if "generic-aarch64" in a["name"]]
sys.exit(f"No {name} in release {version}. generic-aarch64 assets: {others}")
')
url=$1 name=$2 version=$3 digest=$4

echo "Home Assistant OS $version: $name"
curl -fL --progress-bar -o "$work/$name" "$url"
if [ "$digest" != "none" ]; then
  echo "$digest  $work/$name" | shasum -a 256 -c -
else
  echo "The release lists no SHA-256 for $name. Record that in the results."
fi

xz -dc "$work/$name" > "$work/haos.img"
# Home Assistant OS grows its data partition into the free space on first boot.
dd if=/dev/zero of="$work/haos.img" bs=1 count=0 seek=32g 2>/dev/null
echo "$version" > "$work/version"
echo "Ready: $work/haos.img (Home Assistant OS $version)"
