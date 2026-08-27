package com.mindfulhome

object AppVersion {
    const val versionName = "0.105.0"
}

// 0.105.0 - Birds stay after a new timer/extension; clear when Home/Default resumes
// 0.104.2 - Extra focus-gate back-and-forths as the window elapses; never mention the turn floor
// 0.104.1 - Focus-time gate opening asks if remaining window time is truly too urgent to wait
// 0.104.0 - Scripted first gate messages; don't call the model until the user replies
// 0.103.3 - Overlay for notifications; restore Info tutorial pages; detail script/cloud/LM Playground
// 0.103.2 - Replay overlay tour even if the screen already auto-started once this session
// 0.103.1 - Help (i): replay overlay tour; explain notifications and timer extensions
// 0.103.0 - Per-screen spotlight coachmarks; drop text tutorial and onboarding explanation pages
// 0.102.1 - Rebase Google Sign-In extract (AiMode, host activity) onto LM Playground on-device
// 0.102.0 - Rebase onto LM Playground: keep Google Sign-In host, overlay permission, AI Google/on-device/none
// 0.101.2 - Use a preloaded LM Playground model even without tool calling; surface real API errors
// 0.101.1 - Split on-device start helpers; cover local tool invoke
// 0.101.0 - On-device AI via LM Playground AIDL; warn if the app is not installed
// 0.100.0 - App catalog: per-package folder resolve, atomic refresh, manual picker refresh
// 0.99.1 - QL: known media packages take precedence over IMAGE/VIDEO category ignore
// 0.99.0 - Never-block: remove ShouldYouBeHere; QL grace→birds; auto-unrestrict preinstalled (ex media); Karma search
// 0.98.3 - Conversation grace only while banner reply field is focused (not bird tap)
// 0.98.2 - Fix catch-up unit test: debt caps 10× stage advance
// 0.98.1 - Conversation grace for nudge chat only; 10× bird catch-up; defer karma until normal pace
// 0.98.0 - Auto-resume suspended timer when returning to that app during Quick Launch (skip gates)
// 0.97.3 - Karma -1 only when predatory bird ignored after timer expiry (not Quick Launch gate)
// 0.97.2 - Add System default language option (follow device locale)
// 0.97.1 - Fix Service/Application getString ignoring in-app language (wrap locale context)
// 0.97.0 - Localize timer/nudge/QL notification copy + offline nudge fallbacks
// 0.96.1 - Fill missing Italian intent-folder keys; require emit_locale_xml success after i18n edits
// 0.96.0 - Fix onboarding crash: nested verticalScroll in language picker step
// 0.95.0 - Onboarding ends with app-tier + home-layout explanation pages
// 0.94.0 - Ask for app-switch detection (Accessibility) during onboarding setup
// 0.93.0 - Localize preset intent folder names + "something else?" tile
// 0.92.1 - Fix language picker: AppCompatActivity + autoStoreLocales so onboarding follows selection
// 0.92.0 - In-app language picker (onboarding + Settings); string resources; AI write-in-locale
// 0.91.0 - Allowlisted utility QL apps (Settings) get grace on leave; harden unlock instrumented test
// 0.90.0 - Fix SlotFolderOperationsInstrumentedTest casting List as Folder
// 0.89.1 - Document CRAP gate status; fix Kotlin mangled-name joining in compute_crap.py
// 0.89.0 - Finish CRAP<=42: mechanical CC<=6 splits across remaining offenders + Logic tests
// 0.88.3 - DailyLogSummary/SessionLogger/IntentFolder thins; still ~55 CRAP>42 leftovers
// 0.88.2 - More CRAP<=42: todo/icon/session log Logic+tests; onboarding/settings/QL thins
// 0.88.1 - Further CRAP<=42: session/usage/pin/a11y Logic+tests; Timer/IntentFolder thins
// 0.88.0 - CRAP<=42 push: split AppSlotStrip/Logs/Karma/NavHost/QuickLaunch shells + Logic tests
// 0.87.1 - Further thin Home/MissionIntent dialogs/TimerService dispatch; DurationPickerRow split
// 0.87.0 - Aggressive CRAP<=42: Home/Timer/MissionIntent/Overlay/merge Logic+Parts + unit tests
// 0.86.2 - Finish QuickLaunch CC split (row/folder parts, drop helpers, strip dialogs)
// 0.86.1 - Further thin Phase 1 high-CRAP methods (TimerService/Negotiation/Overlay/MainActivity) + Logic tests
// 0.86.0 - Lower QuickLaunch CC: row/folder composable splits + resolveDropAction helpers + strip dialogs
// 0.85.2 - Extract OverlayNudgeLogic + MainActivityLogic; unit-test badge/spawn/banner + intent/auth preflight
// 0.85.1 - Extract TimerServiceLogic; unit-test QL switch/grace/nudge/away/extension decisions
// 0.85.0 - Extract NegotiationManagerLogic + LmClient; inject BackendAuthHelper.generate for tests
// 0.84.9 - Thin SettingsScreen: call section composables only; drop duplicate helpers
// 0.84.8 - NegotiationLogic tests: assert first matching launch-query marker (preserve behavior)
// 0.84.7 - Extract AppSlotStripLogic + SettingsLogic; thin SettingsScreen via sections
// 0.84.6 - Extract NegotiationLogic from NegotiationScreen; unit-test query/mode/launch/match
// 0.84.5 - Ignore generated CRAP markdown reports in git
// 0.84.4 - CRAP: parse detekt 2.0 messages; ignore unit-test failures during crapCheck
// 0.84.3 - Align detekt-crap.yml with detekt 2.0 config keys
// 0.84.2 - Make crapCheck an Exec task; fix detekt basePath types
// 0.84.1 - Fix crapCheck Gradle DSL (basePath string, project.exec)
// 0.84.0 - Add Kover + detekt CRAP metric check (scripts/crap, docs/crap.md)
