# AI gates

MindfulHome uses two short AI conversations before phone time is allowed, plus a separate expired-session confrontation. The AI gates **nag, never block**: you can always keep chatting, and access is only offered via an explicit **Proceed** button once the gate allows it.

## Goals

| Gate | When | Purpose |
|------|------|---------|
| **Focus time gate** | Timer starts during a focus-time window | Check that spending phone time now matches your declared intent (not which app you'll use). |
| **App gatekeeper** | Opening a hidden (karma-hidden) app | Ask why you need it, surface your per-app note and recent usage, push for intentional use. |
| **Should you be here?** | Recents (or similar) returns to a non–Quick Launch app while monitoring treats the session as expired | Instant confrontation screen. Green → leave. Red → in-app chat using the **timer-expired nudge script** (same prompts/tools as the notification, gate chat UI). |

Both AI gates share the same UX pattern:

1. AI opens with a question.
2. You reply (at least one full back-and-forth before Proceed can appear).
3. AI may grant access when satisfied, or the app auto-grants at **max rounds** (see below).
4. **Proceed** appears when access is granted; chat stays open until you tap it.

The AI is **not** told about max rounds. That limit is enforced in app code only.

## Round mechanics

A **round** is one completed back-and-forth: your message after the AI has spoken.

`exchangeCount` in `NegotiationManager` counts completed user rounds (not AI turns).

| Phase | Behavior |
|-------|----------|
| **Before min rounds** | `grantAccess` / `grantTimeAccess` is blocked even if the model calls the tool. |
| **Min … max rounds** | Grant only when the AI is satisfied and calls the grant tool. |
| **At max rounds** | If still not granted, the app **auto-grants** access (Proceed becomes available). |

### Default round limits

**Focus time gate:** min 1, max 1 by default; both configurable in Settings → Behavior → Focus Gate Length (1–6, max is kept ≥ min).

**App gatekeeper:** min from karma and context:

- Base: `ceil(ln(1 + negativeKarma))`, floored at 1
- +1 if focus time is active
- +1 if the app note matches [caution keywords](#caution-keywords) (`requiresExtraConfirmation`)

Max rounds = `min × 2` (at least min).

## Proceed button

- Does **not** auto-launch or continue the session.
- Shown only when `accessGranted` is true (AI grant or max-round auto-grant).
- Requires at least one user message in the thread (min rounds policy).
- Gatekeeper label: **Proceed to {app name}**. Focus gate: **Proceed**.

## Prompts (Settings → Gate prompts)

Each gate has two editable fields:

1. **System prompt** — role and behavior for the model.
2. **Context template** — per-session facts inserted as the first user turn (with dynamic values).

Defaults live in `PromptTemplates.DEFAULT_*`. **Save** stores your copy; **Reset to default** restores built-ins.

The model receives: `systemPrompt` + `context template` (resolved) on conversation start. Edits apply to the next gate conversation.

### Context template syntax

**Placeholders** — `{name}` replaced with session values (see lists below).

**Optional blocks** — `[[ ... ]]`:

- Every `{placeholder}` inside the block must be **non-empty** (not `""`) or the whole block is omitted.
- Example: `[[The user has this to say about the app: "{appNote}". ]]` → omitted when there is no Karma note.

**Gate-only placeholder** — `{cautionGate}`:

- Set by the app to a single space when the app note matches [caution keywords](#caution-keywords), otherwise `""`.
- Use only inside `[[...]]` to show extra caution text; the space is trimmed in final output.

### Gatekeeper placeholders

| Placeholder | Source |
|-------------|--------|
| `{appName}` | App label |
| `{karmaScore}`, `{totalOpens}`, `{totalOverruns}`, `{timesRequestedToday}` | Karma record |
| `{minRounds}` | Computed min rounds for this open |
| `{focusModeActive}` | `true` / `false` |
| `{appNote}` | Per-app note from Karma screen |
| `{confrontationBrief}` | Usage snapshot from last timer (rank, foreground time, longest sessions) |
| `{cautionGate}` | Non-empty when note matches caution keywords (gates optional text only) |

### Focus gate placeholders

| Placeholder | Source |
|-------------|--------|
| `{durationMinutes}` | Timer session length |
| `{declaredIntent}` | Reason entered on timer screen |
| `{focusWindowDescription}` | Active focus window label |
| `{minRounds}` | Min rounds (from Settings, default 1) |

### Caution keywords

If the Karma note contains phrases like `don't open`, `avoid`, `doomscroll`, `bedtime`, etc., the app:

- Adds +1 to gatekeeper min rounds
- Sets `{cautionGate}` so your optional caution block can appear
- Can delay direct launch in general chat (`requiresExtraConfirmation`)

Keyword list: `PromptTemplates.requiresExtraConfirmation`.

## Offline fallback

Without backend AI or on-device model, scripted replies in `PromptTemplates.fallback*` run the same round policy. Proceed still requires `fallbackShouldGrantAccess` (≥ 2 user rounds) before access is granted in fallback mode.

## Code map

| Piece | Location |
|-------|----------|
| Default prompts & template engine | `app/src/main/java/com/mindfulhome/ai/PromptTemplates.kt` |
| Round policy & auto-grant at max | `NegotiationManager.applyGatekeeperRoundPolicy` |
| Settings persistence | `SettingsManager` gate prompt keys |
| UI (chat + Proceed) | `ui/negotiation/NegotiationScreen.kt` |
| Prompt editors | `ui/settings/SettingsScreen.kt` → Gate prompts |
| Navigation entry | `MainActivity` routes `assistant`, `negotiate/{packageName}` |

## Navigation

- Focus time active → timer **Start** → `assistant` (focus gate).
- Hidden app from home → `negotiate/{packageName}` (gatekeeper).

See [navigation-map.md](navigation-map.md) for full route graph.
