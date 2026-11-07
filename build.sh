#!/bin/bash
# One command to build everything:  ./build.sh   →  dist/PerformanceKeeper.apk
# First time only:                   ./scripts/setup-toolchain.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
[ -d "$ROOT/toolchain/jdk" ] || "$ROOT/scripts/setup-toolchain.sh"
"$ROOT/fanctl/build.sh"
"$ROOT/app/build.sh"
mkdir -p "$ROOT/dist" && cp "$ROOT/app/PerformanceKeeper.apk" "$ROOT/dist/"
echo "✔ dist/PerformanceKeeper.apk"
