package com.mindfulhome.ui.settings

import com.mindfulhome.settings.SettingsManager

data class PermissionCardCopy(
    val title: String,
    val description: String,
    val actionLabel: String?,
)

enum class SettingsPermissionKind {
    UsageAccess,
    Notification,
    Overlay,
    Accessibility,
}

fun permissionCardCopy(
    kind: SettingsPermissionKind,
    granted: Boolean,
    skippedPrompt: Boolean = false,
): PermissionCardCopy = when (kind) {
    SettingsPermissionKind.UsageAccess -> usageAccessCardCopy(granted, skippedPrompt)
    SettingsPermissionKind.Notification -> notificationCardCopy(granted, skippedPrompt)
    SettingsPermissionKind.Overlay -> overlayCardCopy(granted, skippedPrompt)
    SettingsPermissionKind.Accessibility -> accessibilityCardCopy(granted)
}

private fun usageAccessCardCopy(granted: Boolean, skippedPrompt: Boolean) = PermissionCardCopy(
    title = "Usage Access",
    description = when {
        granted -> "Granted. MindfulHome can track which app is in the foreground."
        skippedPrompt -> "Missing. You chose to skip permission reminders. Grant anytime from here."
        else -> "Required for karma tracking. Tap to grant."
    },
    actionLabel = if (granted) null else "Grant",
)

private fun notificationCardCopy(granted: Boolean, skippedPrompt: Boolean) = PermissionCardCopy(
    title = "Notification Permission",
    description = when {
        granted -> "Granted. MindfulHome can show timer and nudge notifications."
        skippedPrompt -> "Missing. You chose to skip permission reminders. Grant anytime from here."
        else -> "Required for timer countdown and nudge notifications."
    },
    actionLabel = if (granted) "Open Settings" else "Grant",
)

private fun overlayCardCopy(granted: Boolean, skippedPrompt: Boolean) = PermissionCardCopy(
    title = "Overlay Permission",
    description = when {
        granted -> "Granted. Nudge reminders will appear over any app."
        skippedPrompt -> "Missing. You chose to skip permission reminders. Grant anytime from here."
        else -> "Not granted. Nudges will only appear as notifications, " +
            "which Android may silence over time. Tap to grant."
    },
    actionLabel = if (granted) null else "Grant",
)

private fun accessibilityCardCopy(granted: Boolean) = PermissionCardCopy(
    title = if (granted) {
        "App-switch detection — On ✓"
    } else {
        "App-switch detection — Off (optional)"
    },
    description = if (granted) {
        "On. MindfulHome is notified the instant you switch apps, so it reacts " +
            "immediately and no longer polls in the background — better for battery. " +
            "It only reads which app is in front, never your screen content."
    } else {
        "Off. Right now MindfulHome checks the foreground app on a timer, which " +
            "uses more battery. Turn this on and MindfulHome is told the moment you " +
            "switch apps instead — faster reactions, less battery. It reads only " +
            "which app is in front, never your screen content.\n\n" +
            "To enable: tap below, find MindfulHome under Installed/Downloaded apps, " +
            "and switch it on."
    },
    actionLabel = if (granted) "Open Accessibility settings" else "Enable",
)

fun formatMinutesOfDay(minutes: Int): String {
    val clamped = minutes.coerceIn(0, 1439)
    val hour = clamped / 60
    val minute = clamped % 60
    return "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
}

fun suggestedNewInterval(
    existing: List<SettingsManager.FocusInterval>,
): SettingsManager.FocusInterval {
    val seed = existing.lastOrNull()?.endMinutes ?: (9 * 60)
    val end = (seed + 60) % (24 * 60)
    return SettingsManager.FocusInterval(startMinutes = seed, endMinutes = end)
}

/** Returns [raw] when empty or all digits; otherwise null (reject non-digit input). */
fun parseNonNegativeIntOrEmpty(raw: String): String? =
    if (raw.isEmpty() || raw.all { it.isDigit() }) raw else null

fun onDeviceModelDescription(hasModel: Boolean, sharedDirPath: String): String =
    if (hasModel) {
        "Model installed. AI features are active."
    } else {
        "No model found. Download Gemma3-1B-IT (.litertlm) from " +
            "HuggingFace (557 MB) and push it via adb:\n\n" +
            "adb push model.litertlm $sharedDirPath/\n\n" +
            "The app checks both $sharedDirPath/ and the app-private models dir. " +
            "Without a model, fallback scripted responses will be used."
    }

fun coerceDailySummaryRegenerateN(
    raw: String,
    min: Int,
    max: Int,
): Int = raw.toIntOrNull()?.coerceIn(min, max) ?: 0

fun dailySummaryPromptVersionLabel(version: Int): String =
    "Prompt version: $version (0 = default; increments when you save new instructions)"

/** Toast suffix after saving daily-summary prompt (+ optional regenerate). */
fun dailySummaryRegenerateToastSuffix(
    regenerateN: Int,
    tokenBlank: Boolean,
    candidateDays: Int,
    successCount: Int,
): String {
    if (regenerateN <= 0) return ""
    if (tokenBlank) return " Sign in to remote AI to regenerate summaries."
    return when {
        candidateDays == 0 ->
            " No stored summaries had an older prompt version (nothing to refresh)."
        successCount == candidateDays ->
            " Refreshed $successCount day(s)."
        else ->
            " Refreshed $successCount of $candidateDays day(s); " +
                "others left unchanged after API or JSON errors."
    }
}

fun backendSignInErrorMessage(statusCode: Int, code: String?): String = when {
    statusCode == 401 -> "Sign-in rejected. Please try again."
    statusCode == 403 && code == "PENDING_APPROVAL" -> "Your account is pending approval."
    statusCode == 403 && code == "ACCESS_REFUSED" -> "Your account access has been refused."
    statusCode == 429 -> "Too many sign-in attempts. Please try again later."
    else -> "Backend sign-in failed: HTTP $statusCode"
}

fun backendSignInErrorMessage(e: com.mindfulhome.ai.backend.BackendHttpException): String =
    backendSignInErrorMessage(e.statusCode, e.code)
