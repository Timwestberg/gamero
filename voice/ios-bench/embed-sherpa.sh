#!/usr/bin/env bash
# Copy sherpa-onnx.framework into the app bundle. The Xcode project links it
# with -framework, which does not embed it, so a device process cannot dyld it.
# User-script sandboxing is off so this can read SHERPA_ONNX_FRAMEWORKS.
set -eu
if [ -z "${SHERPA_ONNX_FRAMEWORKS:-}" ]; then
  echo "embed-sherpa: SHERPA_ONNX_FRAMEWORKS is unset, so the framework was not copied."
  exit 0
fi
src="${SHERPA_ONNX_FRAMEWORKS}/sherpa-onnx.framework"
if [ ! -d "$src" ]; then
  echo "embed-sherpa: missing $src" >&2
  exit 1
fi
dest="${TARGET_BUILD_DIR:?}/${FRAMEWORKS_FOLDER_PATH:?}"
mkdir -p "$dest"
rm -rf "${dest}/sherpa-onnx.framework"
cp -R "$src" "${dest}/sherpa-onnx.framework"
if [ -n "${EXPANDED_CODE_SIGN_IDENTITY:-}" ] && [ "$EXPANDED_CODE_SIGN_IDENTITY" != "-" ] && [ "${CODE_SIGNING_ALLOWED:-YES}" != "NO" ]; then
  codesign --force --sign "$EXPANDED_CODE_SIGN_IDENTITY" --preserve-metadata=identifier,entitlements "${dest}/sherpa-onnx.framework"
fi
