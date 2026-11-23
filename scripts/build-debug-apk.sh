#!/bin/zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
# KUAN builds against the toolchain bundled with the sibling workout2-app
# checkout — JDK, Android SDK and the gradle distribution all live there.
# Without that checkout, the usual JAVA_HOME / ANDROID_HOME from the
# environment (or local.properties) are used instead.
TOOLING="$ROOT_DIR/../workout2-app/.tooling"
if [ -d "$TOOLING" ]; then
  export JAVA_HOME="$TOOLING/jdk/Contents/Home"
  export ANDROID_SDK_ROOT="$TOOLING/android-sdk"
fi

cd "$ROOT_DIR"
./gradlew assembleDebug

echo
echo "APK:"
ls -t "$ROOT_DIR"/app/build/outputs/apk/debug/Liliang-R*.apk | head -1
echo "To install on a connected device or emulator: scripts/install-debug-apk.sh"
