#!/usr/bin/env bash
set -euo pipefail
COLOR_UP_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$COLOR_UP_ROOT/scripts/android-env.sh"
cd "$COLOR_UP_ROOT"
exec ./gradlew "$@"
