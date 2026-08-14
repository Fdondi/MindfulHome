package com.mindfulhome.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mindfulhome.R
import com.mindfulhome.ai.LmPlaygroundManager
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
    context: Context,
    kind: SettingsPermissionKind,
    granted: Boolean,
    skippedPrompt: Boolean = false,
    permissionTitle: String? = null,
    accessibilityEnabled: Boolean = false,
): PermissionCardCopy = permissionCardCopy(
    getString = context::getString,
    kind = kind,
    granted = granted,
    skippedPrompt = skippedPrompt,
    permissionTitle = permissionTitle,
    accessibilityEnabled = accessibilityEnabled,
)

internal fun permissionCardCopy(
    getString: (Int) -> String,
    kind: SettingsPermissionKind,
    granted: Boolean,
    skippedPrompt: Boolean = false,
    permissionTitle: String? = null,
    accessibilityEnabled: Boolean = false,
): PermissionCardCopy = when (kind) {
    SettingsPermissionKind.UsageAccess -> {
        require(permissionTitle != null)
        usageAccessCardCopy(
            getString,
            granted,
            skippedPrompt,
            permissionTitle,
            accessibilityEnabled,
        )
    }
    SettingsPermissionKind.Notification -> {
        require(permissionTitle != null)
        notificationCardCopy(getString, granted, skippedPrompt, permissionTitle)
    }
    SettingsPermissionKind.Overlay -> {
        require(permissionTitle != null)
        overlayCardCopy(getString, granted, skippedPrompt, permissionTitle)
    }
    SettingsPermissionKind.Accessibility -> accessibilityCardCopy(getString, granted)
}

private fun usageAccessCardCopy(
    getString: (Int) -> String,
    granted: Boolean,
    skippedPrompt: Boolean,
    title: String,
    accessibilityEnabled: Boolean,
) = PermissionCardCopy(
    title = title,
    description = usageAccessDescription(
        getString,
        granted,
        skippedPrompt,
        accessibilityEnabled,
    ),
    actionLabel = if (granted) null else getString(R.string.grant),
)

private fun usageAccessDescription(
    getString: (Int) -> String,
    granted: Boolean,
    skippedPrompt: Boolean,
    accessibilityEnabled: Boolean,
): String = when {
    granted -> getString(R.string.perm_usage_granted)
    accessibilityEnabled -> getString(R.string.perm_usage_optional_a11y)
    skippedPrompt -> getString(R.string.perm_skipped)
    else -> getString(R.string.perm_usage_required)
}

private fun notificationCardCopy(
    getString: (Int) -> String,
    granted: Boolean,
    skippedPrompt: Boolean,
    title: String,
) = PermissionCardCopy(
    title = title,
    description = when {
        granted -> getString(R.string.perm_notification_granted)
        skippedPrompt -> getString(R.string.perm_skipped)
        else -> getString(R.string.perm_notification_required)
    },
    actionLabel = if (granted) {
        getString(R.string.open_settings)
    } else {
        getString(R.string.grant)
    },
)

private fun overlayCardCopy(
    getString: (Int) -> String,
    granted: Boolean,
    skippedPrompt: Boolean,
    title: String,
) = PermissionCardCopy(
    title = title,
    description = when {
        granted -> getString(R.string.perm_overlay_granted)
        skippedPrompt -> getString(R.string.perm_skipped)
        else -> getString(R.string.perm_overlay_required)
    },
    actionLabel = if (granted) null else getString(R.string.grant),
)

private fun accessibilityCardCopy(getString: (Int) -> String, granted: Boolean) = PermissionCardCopy(
    title = if (granted) {
        getString(R.string.perm_accessibility_on_title)
    } else {
        getString(R.string.perm_accessibility_off_title)
    },
    description = if (granted) {
        getString(R.string.perm_accessibility_on_description)
    } else {
        getString(R.string.perm_accessibility_off_description)
    },
    actionLabel = if (granted) {
        getString(R.string.open_accessibility_settings)
    } else {
        getString(R.string.enable)
    },
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

fun onDeviceModelDescription(installed: Boolean): String =
    if (installed) {
        "LM Playground is installed. On-device conversations use its local models."
    } else {
        "LM Playground is not installed. Install it to run on-device AI. " +
            "Without it, conversations use scripted fallbacks."
    }

fun onDeviceModelLabel(installed: Boolean): String =
    if (installed) {
        "On-device (LM Playground)"
    } else {
        "On-device (LM Playground not installed)"
    }

fun lmPlaygroundInstallUris(): List<String> = listOf(
    LmPlaygroundManager.PLAY_STORE_MARKET_URI,
    LmPlaygroundManager.PLAY_STORE_WEB_URI,
    LmPlaygroundManager.SOURCE_URL,
)

fun openLmPlaygroundInstall(context: Context) {
    lmPlaygroundInstallUris().forEach { uri ->
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next URI (Play Store app → Play web → GitHub).
        }
    }
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

fun backendSignInErrorMessage(statusCode: Int, code: String?): String =
    com.mindfulhome.ai.backend.backendSignInErrorMessage(statusCode, code)

/**
 * Credential Manager throws `NoCredentialException` when One Tap has no saved
 * credential, even if a Google account is already on the device. Opening
 * [android.provider.Settings.ACTION_ADD_ACCOUNT] then fails because that
 * account already exists.
 *
 * Only launch add-account when we positively know there are zero Google
 * accounts. `null` (unknown) must not launch it.
 */
fun shouldOpenAddGoogleAccountAfterNoCredential(knownGoogleAccountCount: Int?): Boolean =
    knownGoogleAccountCount == 0

fun backendSignInErrorMessage(e: com.mindfulhome.ai.backend.BackendHttpException): String =
    backendSignInErrorMessage(e.statusCode, e.code)
