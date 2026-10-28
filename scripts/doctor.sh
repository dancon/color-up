#!/usr/bin/env bash
set -euo pipefail
COLOR_UP_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$COLOR_UP_ROOT/scripts/android-env.sh"
java -version
if [[ -z "${ANDROID_HOME:-}" || ! -d "$ANDROID_HOME/platforms/android-36" ]]; then
    echo 'Missing Android SDK 36. Install platforms;android-36 in Android Studio SDK Manager.' >&2
    exit 1
fi
if [[ ! -d "$ANDROID_HOME/build-tools/36.0.0" ]]; then
    echo 'Missing build-tools;36.0.0. Install it in Android Studio SDK Manager.' >&2
    exit 1
fi
echo "SDK: $ANDROID_HOME"
echo 'Gradle wrapper: 8.13; compile/target SDK: 36; minimum SDK: 26'
if command -v adb >/dev/null 2>&1; then adb devices -l; fi
if command -v emulator >/dev/null 2>&1; then emulator -list-avds; fi
