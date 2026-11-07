#!/bin/bash
# Builds PerformanceKeeper.apk with the local toolchain (no Gradle / Android Studio needed).
#   ./build.sh            build only
#   ./build.sh install    build + install on the projector over Wi-Fi
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(dirname "$HERE")"
T="$ROOT/toolchain"
JH="$T/jdk/Contents/Home"; AAPT2="$T/aapt2dir/aapt2"; AJAR="$T/android.jar"; R8="$T/r8.jar"
B="$HERE/build"
rm -rf "$B"; mkdir -p "$B/classes" "$B/dex" "$B/gen" "$B/assets"

echo "• icons";      "$JH/bin/java" -Djava.awt.headless=true "$HERE/tools/MakeIcons.java" "$B/resraw/drawable" "$ROOT/docs"
echo "• resources";  "$AAPT2" compile --dir "$B/resraw" -o "$B/res.zip"
[ -f "$ROOT/fanctl/fanctl.jar" ] || "$ROOT/fanctl/build.sh" >/dev/null
cp "$ROOT/keeper.sh" "$ROOT/fanctl/fanctl.jar" "$B/assets/"
"$AAPT2" link -o "$B/base.apk" -I "$AJAR" --manifest "$HERE/AndroidManifest.xml" -A "$B/assets" --java "$B/gen" "$B/res.zip"
echo "• java";       "$JH/bin/javac" -nowarn --release 8 -cp "$AJAR" -d "$B/classes" \
                       $(find "$HERE/src" "$B/gen" -name '*.java')
echo "• dex";        "$JH/bin/java" -cp "$R8" com.android.tools.r8.D8 --release --min-api 26 --lib "$AJAR" \
                       --output "$B/dex" $(find "$B/classes" -name '*.class')
cp "$B/base.apk" "$B/unsigned.apk"; (cd "$B/dex" && zip -q -j "$B/unsigned.apk" classes.dex)
KS="$HERE/keystore.jks"
[ -f "$KS" ] || "$JH/bin/keytool" -genkeypair -keystore "$KS" -storepass android -keypass android -alias perfkeeper \
                  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Performance Keeper" >/dev/null 2>&1
echo "• sign";       "$JH/bin/jarsigner" -keystore "$KS" -storepass android -keypass android -sigalg SHA256withRSA -digestalg SHA-256 \
                       -signedjar "$HERE/PerformanceKeeper.apk" "$B/unsigned.apk" perfkeeper >/dev/null
ls -la "$HERE/PerformanceKeeper.apk"

if [ "${1:-}" = install ]; then
  ADB="$ROOT/platform-tools/adb"; IP=${XGIMI_IP:-$(cat "$ROOT/config" 2>/dev/null || true)}; [ -n "$IP" ] || { echo "set XGIMI_IP=<projector ip>"; exit 1; }
  "$ADB" connect "$IP:5555" >/dev/null
  "$ADB" -s "$IP:5555" install -r "$HERE/PerformanceKeeper.apk"
fi
