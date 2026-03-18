# MindfulHome

A mindful Android home launcher that nags, never blocks.

## Philosophy

MindfulHome helps you use your phone more intentionally through soft nudges, not hard blocks. No app is ever force-closed or hard-blocked. Instead:

- **Timer-first**: Set how long you want to use your phone each time you unlock it
- **Default page first**: Land on a customizable default page with widgets and QuickLaunch, then jump to timer when needed
- **Karma system**: Apps earn negative karma when you overrun your timer; positive karma recovers when you stick to it
- **Graduated hiding**: Apps with sustained poor karma disappear from the home screen
- **AI gatekeeper**: Hidden apps can only be accessed by talking to an on-device AI that gently pushes back, but always ultimately relents
- **No force**: You are always in control

## Development Environment

MindfulHome uses a single development workflow:

- **Standard (local development):** Android Studio + local Android SDK/JDK on your machine

If you are using AI assistant rules, keep environment assumptions **project-scoped** in `.cursor/rules/` for this repository rather than as global rules.

## Building

### With Android Studio (standard development workflow)

Open the project in Android Studio. It will sync Gradle automatically. Use Run/Debug as usual -- debugger, logcat, layout inspector all work normally.

**Prerequisites:** Android Studio with SDK 35 and JDK 17.
**Build performance:** Gradle configuration cache is enabled by default in `gradle.properties`.

### Modules

- `:app` - MindfulHome launcher

## Todo Companion integration

- Todo Companion is integrated directly inside MindfulHome's default page as a built-in widget card.
- A task supports: intent text, expected duration, optional deadline, and priority (`P1` to `P4`, default `P2`).
- Ordering is shared between app list and widget:
  - deadline tasks first, sorted by `(duration * priority) / timeToDeadline`
  - non-deadline tasks next, sorted by priority descending
- Start action integration:
  - if MindfulHome is installed, `Start` opens `MainActivity` with timer prefill extras
  - if not installed, Todo Companion shows a clear fallback message and does not fake success

## New default page

- After onboarding, MindfulHome starts on a default page route.
- The default page includes:
  - hosted widgets (plus Add/Remove controls),
  - QuickLaunch apps,
  - a bottom `something else?` button that opens the timer without prefill.
- QuickLaunch pull-tab on the timer page has been removed.
- Navigation map: see `docs/navigation-map.md` for route/state transitions and unlock flow behavior.

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
