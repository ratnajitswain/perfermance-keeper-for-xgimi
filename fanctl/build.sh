#!/bin/bash
# Builds fanctl.jar — the tiny Java helper that reads XGIMI temperature sensors and sets fan speed.
#   ./build.sh                 build only
#   ./build.sh run [args...]   build, copy to the projector and run it (default: probe)
#                              needs XGIMI_IP=<projector ip> or a ../config file with the IP
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
JAVA_HOME="$ROOT/toolchain/jdk/Contents/Home"
R8="$ROOT/toolchain/r8.jar"
[ -x "$JAVA_HOME/bin/javac" ] && [ -f "$R8" ] || { echo "toolchain missing — run scripts/setup-toolchain.sh"; exit 1; }

B="$HERE/build"; rm -rf "$B"; mkdir -p "$B/classes" "$B/dex"
"$JAVA_HOME/bin/javac" --release 8 -d "$B/classes" "$HERE/Fanctl.java"
"$JAVA_HOME/bin/java" -cp "$R8" com.android.tools.r8.D8 --release --min-api 30 --output "$B/dex" "$B/classes/"*.class
rm -f "$HERE/fanctl.jar"; (cd "$B/dex" && zip -q "$HERE/fanctl.jar" classes.dex)
echo "built $HERE/fanctl.jar"

if [ "${1:-}" = run ]; then
  shift
  IP=${XGIMI_IP:-$(cat "$ROOT/config" 2>/dev/null || true)}
  [ -n "$IP" ] || { echo "set XGIMI_IP=<projector ip>"; exit 1; }
  ADB="$ROOT/platform-tools/adb"; RD=/data/local/tmp/xgimi-keeper
  "$ADB" connect "$IP:5555" >/dev/null
  "$ADB" -s "$IP:5555" shell "mkdir -p $RD"
  "$ADB" -s "$IP:5555" push "$HERE/fanctl.jar" "$RD/fanctl.jar" >/dev/null
  "$ADB" -s "$IP:5555" shell "CLASSPATH=$RD/fanctl.jar app_process32 /system/bin Fanctl ${*:-probe}"
fi
