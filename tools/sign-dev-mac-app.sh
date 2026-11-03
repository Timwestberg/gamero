#!/bin/bash
# Sign a local macOS app image with an Apple Development identity and the hardened runtime, for
# checks that need a signed build (the project workspace spike's "terminal libraries load signed").
# No notarization, no timestamp: development only.
#
# Compose's own signing (GAMERO_MAC_SIGN_IDENTITY, docs/release.md) takes only "Developer ID
# Application" and "3rd Party Mac Developer Application" identities: it prefixes any other name with
# "Developer ID Application: ". This script does what Compose's signer does, by hand:
#   1. re-signs the native libraries inside the app's jars, which libraries unpack at run time;
#   2. signs every other Mach-O file in the bundle, innermost first, restoring the executable bit
#      on executables that lost it when Compose copied the app resources;
#   3. signs the runtime and then the app, with the hardened runtime and the entitlements.
#
# Usage:
#   GAMERO_MAC_DEV_IDENTITY="Apple Development: Name (ID)" tools/sign-dev-mac-app.sh <App.app> [entitlements.plist]
#
# Without an entitlements file it uses Compose's defaults: allow-jit and
# allow-unsigned-executable-memory for the JVM, and disable-library-validation.
#
# Build the app image first, marked as a development build so development-gated features open:
#   GAMERO_BROWSER_JAVA_HOME=<JBR SDK> ./gradlew :composeApp:createDistributable -Pgamero.developmentPackage=true
#   -> composeApp/build/compose/binaries/main/app/org.formulagen.wrapper.app
set -euo pipefail

app="${1:?Usage: $0 <App.app> [entitlements.plist]}"
entitlements="${2:-}"
identity="${GAMERO_MAC_DEV_IDENTITY:?Set GAMERO_MAC_DEV_IDENTITY to a codesigning identity (security find-identity -v -p codesigning)}"
[[ -d "$app/Contents" ]] || { echo "Not an app bundle: $app" >&2; exit 1; }
[[ "$(uname -s)" == Darwin ]] || { echo "macOS only" >&2; exit 1; }

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
if [[ -z "$entitlements" ]]; then
  entitlements="$work/entitlements.plist"
  cat >"$entitlements" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>com.apple.security.cs.allow-jit</key><true/>
    <key>com.apple.security.cs.allow-unsigned-executable-memory</key><true/>
    <key>com.apple.security.cs.disable-library-validation</key><true/>
</dict>
</plist>
PLIST
fi

sign() { codesign --force --timestamp=none --options runtime --sign "$identity" "$@"; }
is_macho() { file -b "$1" | grep -q '^Mach-O'; }
is_executable_macho() { file -b "$1" | grep -qE '^Mach-O.*executable'; }

# 1. Native libraries inside jars.
count=0
while IFS= read -r -d '' jar; do
  entries=()
  while IFS= read -r entry; do entries+=("$entry"); done < <(zipinfo -1 "$jar" 2>/dev/null | grep -E '\.(dylib|jnilib)$' || true)
  [[ ${#entries[@]} -gt 0 ]] || continue
  unpacked="$work/jar-$count"; mkdir -p "$unpacked"
  (cd "$unpacked" && unzip -q "$jar" "${entries[@]}")
  for entry in "${entries[@]}"; do is_macho "$unpacked/$entry" && sign "$unpacked/$entry"; done
  (cd "$unpacked" && zip -q "$jar" "${entries[@]}")
  count=$((count + 1))
done < <(find "$app/Contents" -name '*.jar' -print0)
echo "sign-dev-mac-app: re-signed natives inside $count jar(s)"

# 2. Every Mach-O file, deepest first, executables with the entitlements.
signed=0
while IFS= read -r file; do
  is_macho "$file" || continue
  if is_executable_macho "$file"; then
    chmod +x "$file"
    sign --entitlements "$entitlements" "$file"
  else
    sign "$file"
  fi
  signed=$((signed + 1))
done < <(find "$app/Contents" -type f ! -name '*.jar' | awk -F/ '{ print NF "\t" $0 }' | sort -rn | cut -f2-)
echo "sign-dev-mac-app: signed $signed Mach-O file(s)"

# 3. The runtime bundle, then the app.
[[ -d "$app/Contents/runtime" ]] && sign --entitlements "$entitlements" "$app/Contents/runtime"
sign --entitlements "$entitlements" "$app"
codesign --verify --deep --strict --verbose=1 "$app"
codesign -d --verbose=2 "$app" 2>&1 | grep -E '^(Authority|TeamIdentifier|CodeDirectory)' | head -3
