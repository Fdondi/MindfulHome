#!/usr/bin/env bash
# Run on-device LM Playground probe + local-fallback instrumented tests.
# See docs/test/lm-playground-emulator.md — last run froze/segfaulted on a 26 GB laptop; live tests did not finish.
# Writes logcat for the probe tag to results/test/lm-playground-probe.log.
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p results/test
SERIAL="${ANDROID_SERIAL:-}"
if [ -z "$SERIAL" ]; then
  SERIAL="$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')"
fi
if [ -z "$SERIAL" ]; then
  echo "No emulator/device connected. Start an AVD first." >&2
  exit 1
fi
export ANDROID_SERIAL="$SERIAL"
echo "See docs/test/lm-playground-emulator.md (last laptop run froze with all cores at 100%, then qemu segfaulted)."
adb -s "$SERIAL" logcat -c || true
CLASS_FALLBACK=com.mindfulhome.ai.LocalLmFallbackInstrumentedTest
CLASS_PROBE=com.mindfulhome.ai.LmPlaygroundProbeInstrumentedTest
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class="$CLASS_FALLBACK"
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class="$CLASS_PROBE"
adb -s "$SERIAL" logcat -d -s LmPlaygroundProbe:I NegotiationManager:D LmPlaygroundManager:E > results/test/lm-playground-probe.log
echo "Wrote results/test/lm-playground-probe.log"
