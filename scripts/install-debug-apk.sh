#!/bin/zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ADB="$ROOT_DIR/../workout2-app/.tooling/android-sdk/platform-tools/adb"
# Without the sibling toolchain, fall back to the adb on PATH.
[ -x "$ADB" ] || ADB="$(command -v adb || true)"
# Newest Liliang-R<versionCode>.apk (the outputs are named per release round).
APK="$(ls -t "$ROOT_DIR"/app/build/outputs/apk/debug/Liliang-R*.apk 2>/dev/null | head -1)"

if [ ! -x "$ADB" ]; then
  echo "adb not found: $ADB" >&2
  exit 1
fi

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "APK not found: $APK" >&2
  echo "Run scripts/build-debug-apk.sh first." >&2
  exit 1
fi

"$ADB" install -r "$APK"
