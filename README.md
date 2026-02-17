# MindfulHome

A mindful Android home launcher that nags, never blocks.

## Philosophy

MindfulHome helps you use your phone more intentionally through soft nudges, not hard blocks. No app is ever force-closed or hard-blocked. Instead:

- **Timer-first**: Set how long you want to use your phone each time you unlock it
- **Karma system**: Apps earn negative karma when you overrun your timer; positive karma recovers when you stick to it
- **Graduated hiding**: Apps with sustained poor karma disappear from the home screen
- **AI gatekeeper**: Hidden apps can only be accessed by talking to an on-device AI that gently pushes back, but always ultimately relents
- **No force**: You are always in control

## Building

### With Android Studio (recommended for development)

Open the project in Android Studio. It will sync Gradle automatically. Use Run/Debug as usual -- debugger, logcat, layout inspector all work normally.

**Prerequisites:** Android Studio with SDK 35 and JDK 17.

### With Podman (optional, for reproducible CI builds)

A `Containerfile` and build script are provided for headless builds without a local Android SDK:

```powershell
# Windows
.\build-in-container.ps1 assembleDebug

# The APK will be at app/build/outputs/apk/debug/app-debug.apk
```

### AI Model (Optional)

For on-device AI features, download a [Gemma3-1B-IT .litertlm model](https://huggingface.co/litert-community/Gemma3-1B-IT) (557 MB) and place it in the app's internal `files/models/` directory on the device.

Without a model, MindfulHome uses scripted fallback responses that still create the same reflective friction.

## Architecture

- **Kotlin** + **Jetpack Compose** (Material 3)
- **LiteRT-LM** for on-device LLM inference
- **Room** database for karma scores and usage history
- **Foreground Service** for timer countdown and nudge notifications

## License

MIT
