## Cursor Cloud specific instructions

### Project Overview

MindfulHome is an Android home launcher app (Kotlin + Jetpack Compose). There is no backend to run locally; the only build artifact is an APK. See `README.md` for architecture details.

### Build Commands

All Gradle commands must be run with `JAVA_HOME=/opt/jbr` and use the standalone Gradle at `/opt/gradle/bin/gradle` (not `./gradlew`). See the daemon workaround below for why.

| Task | Command |
|---|---|
| Build debug APK | `JAVA_HOME=/opt/jbr /opt/gradle/bin/gradle assembleDebug` |
| Run lint | `JAVA_HOME=/opt/jbr /opt/gradle/bin/gradle lint` |
| Run unit tests | `JAVA_HOME=/opt/jbr /opt/gradle/bin/gradle testDebugUnitTest` |
| Build release APK | `JAVA_HOME=/opt/jbr /opt/gradle/bin/gradle assembleRelease` |

APK output: `app/build/outputs/apk/debug/app-debug.apk`

### Daemon JVM Criteria Workaround

The file `gradle/gradle-daemon-jvm.properties` specifies `toolchainVendor=jetbrains` but is missing download URLs required by Gradle 9.2.1's daemon auto-provisioning. This prevents `./gradlew` from starting a new daemon.

**Workaround on fresh sessions (when no Gradle daemon is running):**

```bash
cd /workspace
mv gradle/gradle-daemon-jvm.properties gradle/gradle-daemon-jvm.properties.bak
JAVA_HOME=/opt/jbr /opt/gradle/bin/gradle assembleDebug
mv gradle/gradle-daemon-jvm.properties.bak gradle/gradle-daemon-jvm.properties
```

Once the daemon is running, subsequent `JAVA_HOME=/opt/jbr /opt/gradle/bin/gradle <task>` commands work without moving the file.

### Lint Notes

`lint` exits non-zero due to `ProtectedPermissions` errors for `PACKAGE_USAGE_STATS` and `QUERY_ALL_PACKAGES`. These are expected for a home launcher app and are not real issues.

### Testing

No unit test sources exist yet (`testDebugUnitTest` reports `NO-SOURCE`). Instrumented tests require a connected Android device/emulator and cannot be run in this headless environment.

### Environment Details

- **JDK**: JetBrains Runtime 21 at `/opt/jbr`
- **Android SDK**: `/opt/android-sdk` (API 36, build-tools 36.0.0, platform-tools)
- **Gradle**: 9.2.1 standalone at `/opt/gradle/bin/gradle`
- **Environment variables**: `ANDROID_HOME`, `ANDROID_SDK_ROOT`, and PATH additions are set in `~/.bashrc`
