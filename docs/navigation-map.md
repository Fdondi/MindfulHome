# MindfulHome Navigation Map

This document maps how the app moves between launcher states and pages.

## Routes

- `onboarding` - first-run setup (language, permissions, AI provider choice: Google sign-in / local download / scripted none, then app tiers, home-layout explanation, Todo intro).
- `default` - default landing page with Todo widget, resume action, QuickLaunch, and plain timer entry.
- `timer` - timer setup page.
- `home` - app selection page after timer starts.
- `assistant` - focus time gate ([gates.md](gates.md)); `NegotiationScreen` with no target app.
- `negotiate/{packageName}` - app gatekeeper ([gates.md](gates.md)); `NegotiationScreen` for a hidden app.
- `extend/{packageName}` - expire→extend gate; same chat UI as other gates, nudge/extend script.
- `karma`, `settings`, `logs` - auxiliary pages.
- `help` - tutorial index (revisit onboarding explanations).
- `help/{topicId}` - individual tutorial topic page (Previous/Next between topics).

## Startup and unlock entry

Start destination (in `MainActivity`) is chosen with this priority:

1. `onboarding` if onboarding is not complete.
2. `timer` if `MainActivity.shouldShowTimer` is true.
3. `postTimerTargetRoute()` (`home` or `assistant`) if QuickLaunch session is active.
4. `postTimerTargetRoute()` (`home` or `assistant`) if timer is currently running.
5. `default` otherwise.

Unlock trigger behavior:

- `ScreenUnlockReceiver` listens for `ACTION_SCREEN_OFF` and `ACTION_USER_PRESENT`.
- Screen-off records that the user was absent. `USER_PRESENT` is ignored unless that marker exists (overlay / other system UI can fire `USER_PRESENT` while the user was never away). Handling a real return consumes the marker.
- If onboarding is not complete, unlock flow is skipped (stay on onboarding).
- If QuickLaunch session is active, unlock flow is skipped (stay on app).
- If quick-return threshold is met and a resumable saved session exists, unlock timer launch is skipped.
- Otherwise it launches `MainActivity` with `EXTRA_FROM_UNLOCK=true`.

When `MainActivity` handles `EXTRA_FROM_UNLOCK`, it navigates to `default` (not `timer`, `home`, or `assistant`) only after onboarding is complete.

## In-app transitions

### Default page (`default`)

- **Todo row "Start"** -> `timer` with prefill minutes/reason.
- **"something else?" button** -> `timer` without prefill.
- **Info (i) button** -> `help` tutorial index.
- **Resume previous session button** -> starts timer from saved remaining time and directly launches the previously used app intent.
- **QuickLaunch tile tap** -> starts QuickLaunch session and directly launches the selected app intent.

### Timer page (`timer`)

- **Start** -> starts timer and navigates to:
  - `assistant` if focus time is active, else
  - `home`.
- **Back** -> returns to `default`.

### Home and negotiation pages

- `home` -> `assistant` or `negotiate/{packageName}` when AI is requested.
- `home` -> `timer` via timer button.
- `assistant` / `negotiate/{packageName}` -> `timer` via timer button.
- `home`, `assistant`, `negotiate/*` can open `karma`, `settings`, and `logs`.
- `home` can also open `help` (tutorial index).

## AI gates

Focus time and hidden-app opens use `NegotiationScreen` with different prompts (no `packageName` = focus gate). Both require at least one user reply before **Proceed** can appear; access may be granted by the AI or auto-granted at max rounds.

Timer / Quick Launch expiry uses in-place bird nudges (never force-home). Returning to a suspended timed session resumes invisibly. Full behavior: [gates.md](gates.md).

## Background/return behavior (`onResume`)

When app returns from background:

- If QuickLaunch session is active -> navigate to `default`.
- Else if away time is below quick-return threshold and timer was running -> navigate to `home` or `assistant` (`postTimerTargetRoute()`).
- Else -> navigate to `default`.

So post-background behavior returns to `default` in most cases, but quick-return with a running timer routes back to `home`/`assistant`.
