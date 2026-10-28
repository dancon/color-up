#!/usr/bin/env bash
# Source this file. Machine-specific paths stay outside Gradle and source code.
if [[ -z "${JAVA_HOME:-}" ]]; then
    if [[ -d '/Applications/Android Studio.app/Contents/jbr/Contents/Home' ]]; then
        export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
    elif command -v java >/dev/null 2>&1; then
        : # CI and Linux may already have a usable java on PATH.
    fi
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
    if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
        export ANDROID_HOME="$ANDROID_SDK_ROOT"
    elif [[ -d "$HOME/Library/Android/sdk" ]]; then
        export ANDROID_HOME="$HOME/Library/Android/sdk"
    elif [[ -d "$HOME/Android/Sdk" ]]; then
        export ANDROID_HOME="$HOME/Android/Sdk"
    fi
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
if [[ -n "${ANDROID_HOME:-}" ]]; then
    export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
fi
