#!/bin/bash
# Downloads everything needed to build Performance Keeper into ./toolchain and ./platform-tools.
# No Android Studio, Gradle or admin rights needed. Works on macOS (Apple Silicon / Intel) and Linux x64.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
T="$ROOT/toolchain"; mkdir -p "$T"; cd "$T"

case "$(uname -s)-$(uname -m)" in
  Darwin-arm64)  JDK_OS=mac;   JDK_ARCH=aarch64; AAPT2_OS=osx;     PT_OS=darwin ;;
  Darwin-x86_64) JDK_OS=mac;   JDK_ARCH=x64;     AAPT2_OS=osx;     PT_OS=darwin ;;
  Linux-x86_64)  JDK_OS=linux; JDK_ARCH=x64;     AAPT2_OS=linux;   PT_OS=linux ;;
  *) echo "unsupported platform: $(uname -s) $(uname -m)"; exit 1 ;;
esac

AAPT2_VER=8.13.2-14304508
R8_VER=8.5.35

if [ ! -d jdk ]; then
  echo "• JDK 17 (Eclipse Temurin)"
  curl -fsSL -o jdk.tar.gz "https://api.adoptium.net/v3/binary/latest/17/ga/$JDK_OS/$JDK_ARCH/jdk/hotspot/normal/eclipse"
  mkdir -p jdk && tar -xzf jdk.tar.gz -C jdk --strip-components=1 && rm jdk.tar.gz
  # keep one layout for scripts: toolchain/jdk/Contents/Home
  if [ ! -d jdk/Contents/Home ]; then mkdir -p jdk.tmp/Contents && mv jdk jdk.tmp/Contents/Home && mv jdk.tmp jdk; fi
fi
if [ ! -f r8.jar ]; then
  echo "• D8/R8 $R8_VER (Google)"
  curl -fsSL -o r8.jar "https://dl.google.com/android/maven2/com/android/tools/r8/$R8_VER/r8-$R8_VER.jar"
fi
if [ ! -x aapt2dir/aapt2 ]; then
  echo "• aapt2 $AAPT2_VER (Google)"
  curl -fsSL -o aapt2.jar "https://dl.google.com/android/maven2/com/android/tools/build/aapt2/$AAPT2_VER/aapt2-$AAPT2_VER-$AAPT2_OS.jar"
  mkdir -p aapt2dir && unzip -q -o aapt2.jar -d aapt2dir && rm aapt2.jar && chmod +x aapt2dir/aapt2
fi
if [ ! -f android.jar ]; then
  echo "• android.jar (API 30)"
  curl -fsSL -o platform.zip "https://dl.google.com/android/repository/platform-30_r03.zip"
  unzip -q -o -j platform.zip '*/android.jar' -d . && rm platform.zip
fi
if [ ! -x "$ROOT/platform-tools/adb" ]; then
  echo "• adb (Android platform-tools)"
  curl -fsSL -o pt.zip "https://dl.google.com/android/repository/platform-tools-latest-$PT_OS.zip"
  unzip -q -o pt.zip -d "$ROOT" && rm pt.zip
fi
echo "✔ toolchain ready in $T"
