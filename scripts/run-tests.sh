#!/bin/zsh
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
# The sibling workout2-app toolchain when present; otherwise the environment's own.
TOOLING="$ROOT_DIR/../workout2-app/.tooling"
if [ -d "$TOOLING" ]; then
  export JAVA_HOME="$TOOLING/jdk/Contents/Home"
  export ANDROID_SDK_ROOT="$TOOLING/android-sdk"
fi

cd "$ROOT_DIR"
./gradlew testDebugUnitTest
