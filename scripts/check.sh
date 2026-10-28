#!/usr/bin/env bash
set -euo pipefail
COLOR_UP_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
python3 "$COLOR_UP_ROOT/tools/import_palette.py" --check
python3 "$COLOR_UP_ROOT/tools/import_illustrations.py" --self-test --check
exec "$COLOR_UP_ROOT/scripts/gradle.sh" :coloring-core:test :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest "$@"
