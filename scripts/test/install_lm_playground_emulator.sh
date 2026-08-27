#!/usr/bin/env bash
# Build LM Playground (debug) from the local clone and install it on an API 30+ emulator.
# Pixel 4 (API 29) is too old: LM Playground minSdk is 30.
# See docs/test/lm-playground-emulator.md — Fold AVD + native compile pegged all cores and qemu segfaulted on a 26 GB laptop.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
LMP="$ROOT/LMPlayground-server"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/jbr-21.0.11}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

if [ ! -d "$LMP" ]; then
  echo "LMPlayground-server clone not found at $LMP" >&2
  exit 1
fi

pick_serial() {
  if [ -n "${ANDROID_SERIAL:-}" ]; then
    echo "$ANDROID_SERIAL"
    return
  fi
  local serial sdk
  while read -r serial; do
    [ -z "$serial" ] && continue
    sdk="$(adb -s "$serial" shell getprop ro.build.version.sdk | tr -d '\r')"
    if [ "$sdk" -ge 30 ]; then
      echo "$serial"
      return
    fi
  done < <(adb devices | awk 'NR>1 && $2=="device" && $1 ~ /^emulator-/ {print $1}')
}

SERIAL="$(pick_serial || true)"
if [ -z "$SERIAL" ]; then
  echo "No API 30+ emulator is connected. Start Pixel_10_Pro_Fold (or similar)." >&2
  exit 1
fi
SDK="$(adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
if [ "$SDK" -lt 30 ]; then
  echo "LM Playground minSdk is 30; $SERIAL is API $SDK." >&2
  exit 1
fi
export ANDROID_SERIAL="$SERIAL"
echo "Installing onto $SERIAL (API $SDK)"

echo "Initializing llama.cpp submodule..."
git -C "$LMP" submodule update --init --recursive

if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "Installing Android cmdline-tools..."
  tmp="$(mktemp -d)"
  curl -fsSL -o "$tmp/cmdtools.zip" "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
  unzip -q "$tmp/cmdtools.zip" -d "$tmp"
  mkdir -p "$ANDROID_HOME/cmdline-tools/latest"
  cp -a "$tmp/cmdline-tools/." "$ANDROID_HOME/cmdline-tools/latest/"
  rm -rf "$tmp"
fi

echo "Ensuring NDK 27.2.12479018 and CMake 3.31.6..."
yes | sdkmanager --licenses >/dev/null || true
sdkmanager "ndk;27.2.12479018" "cmake;3.31.6"

echo "Building and installing debug LM Playground (CPU-only, x86_64, 2 workers)..."
cd "$LMP"
export CMAKE_BUILD_PARALLEL_LEVEL="${CMAKE_BUILD_PARALLEL_LEVEL:-2}"
./gradlew :app:installDebug -PnoVulkan -Pandroid.injected.build.abi=x86_64 --max-workers=2

echo "Installed com.druk.lmplayground.debug on $SERIAL"
adb -s "$SERIAL" shell pm path com.druk.lmplayground.debug
