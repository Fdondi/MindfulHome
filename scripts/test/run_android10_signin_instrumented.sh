#!/usr/bin/env bash
# Run the Android 10 Google Sign-In reproduction on a connected API 29 emulator.
set -euo pipefail
cd "$(dirname "$0")/../.."
SERIAL="${ANDROID_SERIAL:-}"
if [ -z "$SERIAL" ]; then
  SERIAL="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi
if [ -z "$SERIAL" ]; then
  echo "No emulator/device connected. Start the Pixel 4 API 29 AVD first." >&2
  exit 1
fi
SDK="$(adb -s "$SERIAL" shell getprop ro.build.version.sdk | tr -d '\r')"
if [ "$SDK" != "29" ]; then
  echo "Expected API 29 emulator, got API $SDK on $SERIAL" >&2
  exit 1
fi
export ANDROID_SERIAL="$SERIAL"
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mindfulhome.ai.GoogleSignInAndroid10InstrumentedTest
