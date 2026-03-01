package com.mindfulhome.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persists user preferences using SharedPreferences.
 *
 * Modelled after the distraction-linter SettingsManager with the same
 * AI-mode pattern (on-device vs backend).
 */
object SettingsManager {

    private const val PREF_NAME = "mindfulhome_settings"

    // AI mode
    private const val AI_MODE_KEY = "ai_mode"
    const val AI_MODE_ON_DEVICE = "on_device"
    const val AI_MODE_BACKEND = "backend"

    // Backend model selection
    private const val BACKEND_MODEL_KEY = "backend_model"

    // Quick return window (minutes)
    private const val QUICK_RETURN_THRESHOLD_KEY = "quick_return_threshold_minutes"
    const val DEFAULT_QUICK_RETURN_MINUTES = 3
    const val MIN_QUICK_RETURN_MINUTES = 1
    const val MAX_QUICK_RETURN_MINUTES = 10

    // Escalation threshold (number of nudge cycles before forcing back to timer)
    private const val ESCALATION_THRESHOLD_KEY = "escalation_nudge_threshold"
    const val DEFAULT_ESCALATION_THRESHOLD = 5
    const val MIN_ESCALATION_THRESHOLD = 2
    const val MAX_ESCALATION_THRESHOLD = 15

    // Screen-off timestamp (to compute how long the user was away)
    private const val LAST_SCREEN_OFF_KEY = "last_screen_off_timestamp"

    // Timer-running flag persisted for the BroadcastReceiver to read
    private const val TIMER_RUNNING_KEY = "timer_running"

    // Quick-launch bypass session (no countdown until user opens non-quick-launch app)
    private const val QUICK_LAUNCH_SESSION_ACTIVE_KEY = "quick_launch_session_active"
    private const val QUICK_LAUNCH_PACKAGES_KEY = "quick_launch_packages_csv"

    // Last session (for resume)
    private const val LAST_SESSION_PACKAGE_KEY = "last_session_package"
    private const val LAST_SESSION_TOTAL_DURATION_MS_KEY = "last_session_total_duration_ms"
    private const val LAST_SESSION_STARTED_AT_MS_KEY = "last_session_started_at_ms"
    private const val LAST_SESSION_SUSPENDED_AT_MS_KEY = "last_session_suspended_at_ms"

    // Last declared timer intent (used when forcing user back to timer)
    private const val LAST_DECLARED_MINUTES_KEY = "last_declared_minutes"
    private const val LAST_DECLARED_INTENT_KEY = "last_declared_intent"
    private const val LAST_DECLARED_AT_MS_KEY = "last_declared_at_ms"

    /** Available Vertex AI models the user can pick from. */
    data class ModelOption(val id: String, val label: String, val description: String)

    /** Fallback list used when the backend is unreachable. */
    val AVAILABLE_MODELS = listOf(
        ModelOption("gemini-2.5-flash", "Gemini 2.5 Flash", "Fast, capable, best value (recommended)"),
        ModelOption("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite", "Fastest, lowest cost"),
        ModelOption("gemini-2.5-pro", "Gemini 2.5 Pro", "Most capable, thinking model, higher cost"),
    )

    val DEFAULT_MODEL = AVAILABLE_MODELS.first().id

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getAIMode(context: Context): String =
        prefs(context).getString(AI_MODE_KEY, AI_MODE_ON_DEVICE) ?: AI_MODE_ON_DEVICE

    fun setAIMode(context: Context, mode: String) {
        prefs(context).edit { putString(AI_MODE_KEY, mode) }
    }

    fun getBackendModel(context: Context): String =
        prefs(context).getString(BACKEND_MODEL_KEY, DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun setBackendModel(context: Context, model: String) {
        prefs(context).edit { putString(BACKEND_MODEL_KEY, model) }
    }

    // ── Last session (resume) ────────────────────────────────────────

    data class SavedSession(
        val packageName: String,
        val remainingMs: Long,
        val remainingMinutes: Int,
        val totalDurationMs: Long,
        val startedAtMs: Long,
        val suspendedAtMs: Long?,
    )
    data class LastDeclaredIntent(
        val minutes: Int,
        val intent: String,
        val declaredAtMs: Long,
    )

    fun saveLastSession(
        context: Context,
        packageName: String,
        totalDurationMs: Long,
        startedAtMs: Long,
        suspendedAtMs: Long? = null,
    ) {
        val p = prefs(context)
        val existingPackage = p.getString(LAST_SESSION_PACKAGE_KEY, null)
        val existingTotalMs = p.getLong(LAST_SESSION_TOTAL_DURATION_MS_KEY, 0L)
        val existingStartedAtMs = p.getLong(LAST_SESSION_STARTED_AT_MS_KEY, 0L)
        val existingSuspendedAtMs = p.getLong(LAST_SESSION_SUSPENDED_AT_MS_KEY, 0L)
        val isSameSession = existingPackage == packageName &&
            existingTotalMs == totalDurationMs &&
            existingStartedAtMs == startedAtMs
        val persistedSuspendedAtMs = when {
            suspendedAtMs != null -> suspendedAtMs
            isSameSession && existingSuspendedAtMs > 0L -> existingSuspendedAtMs
            else -> 0L
        }
        p.edit {
            putString(LAST_SESSION_PACKAGE_KEY, packageName)
            putLong(LAST_SESSION_TOTAL_DURATION_MS_KEY, totalDurationMs)
            putLong(LAST_SESSION_STARTED_AT_MS_KEY, startedAtMs)
            putLong(LAST_SESSION_SUSPENDED_AT_MS_KEY, persistedSuspendedAtMs)
        }
    }

    fun getLastSession(context: Context): SavedSession? {
        val p = prefs(context)
        val pkg = p.getString(LAST_SESSION_PACKAGE_KEY, null) ?: return null
        val totalDurationMs = p.getLong(LAST_SESSION_TOTAL_DURATION_MS_KEY, 0L)
        val startedAtMs = p.getLong(LAST_SESSION_STARTED_AT_MS_KEY, 0L)
        val suspendedAtMsRaw = p.getLong(LAST_SESSION_SUSPENDED_AT_MS_KEY, 0L)
        if (pkg.isEmpty() || totalDurationMs <= 0L || startedAtMs <= 0L) return null

        val referenceNowMs = if (suspendedAtMsRaw > 0L) {
            suspendedAtMsRaw
        } else {
            System.currentTimeMillis()
        }
        val elapsedMs = (referenceNowMs - startedAtMs).coerceAtLeast(0L)
        val remainingMs = (totalDurationMs - elapsedMs).coerceAtLeast(0L)
        if (remainingMs <= 0L) return null
        val remainingMinutes = ((remainingMs + 59_999L) / 60_000L).toInt()

        return SavedSession(
            packageName = pkg,
            remainingMs = remainingMs,
            remainingMinutes = remainingMinutes,
            totalDurationMs = totalDurationMs,
            startedAtMs = startedAtMs,
            suspendedAtMs = suspendedAtMsRaw.takeIf { it > 0L },
        )
    }

    fun clearLastSession(context: Context) {
        prefs(context).edit {
            remove(LAST_SESSION_PACKAGE_KEY)
            remove(LAST_SESSION_TOTAL_DURATION_MS_KEY)
            remove(LAST_SESSION_STARTED_AT_MS_KEY)
            remove(LAST_SESSION_SUSPENDED_AT_MS_KEY)
        }
    }

    // ── Last declared timer intent ──────────────────────────────────

    fun saveLastDeclaredIntent(
        context: Context,
        minutes: Int,
        intent: String,
        declaredAtMs: Long = System.currentTimeMillis(),
    ) {
        prefs(context).edit {
            putInt(LAST_DECLARED_MINUTES_KEY, minutes.coerceAtLeast(1))
            putString(LAST_DECLARED_INTENT_KEY, intent.trim())
            putLong(LAST_DECLARED_AT_MS_KEY, declaredAtMs)
        }
    }

    fun getLastDeclaredIntent(context: Context): LastDeclaredIntent? {
        val p = prefs(context)
        val minutes = p.getInt(LAST_DECLARED_MINUTES_KEY, 0)
        val intent = p.getString(LAST_DECLARED_INTENT_KEY, "") ?: ""
        val declaredAtMs = p.getLong(LAST_DECLARED_AT_MS_KEY, 0L)
        if (minutes <= 0 || declaredAtMs <= 0L) return null
        return LastDeclaredIntent(
            minutes = minutes,
            intent = intent,
            declaredAtMs = declaredAtMs,
        )
    }

    // ── Quick return window ─────────────────────────────────────────

    fun getQuickReturnMinutes(context: Context): Int =
        prefs(context).getInt(QUICK_RETURN_THRESHOLD_KEY, DEFAULT_QUICK_RETURN_MINUTES)

    fun setQuickReturnMinutes(context: Context, minutes: Int) {
        prefs(context).edit {
            putInt(QUICK_RETURN_THRESHOLD_KEY, minutes.coerceIn(MIN_QUICK_RETURN_MINUTES, MAX_QUICK_RETURN_MINUTES))
        }
    }

    // ── Escalation threshold ────────────────────────────────────────

    fun getEscalationThreshold(context: Context): Int =
        prefs(context).getInt(ESCALATION_THRESHOLD_KEY, DEFAULT_ESCALATION_THRESHOLD)

    fun setEscalationThreshold(context: Context, threshold: Int) {
        prefs(context).edit {
            putInt(ESCALATION_THRESHOLD_KEY, threshold.coerceIn(MIN_ESCALATION_THRESHOLD, MAX_ESCALATION_THRESHOLD))
        }
    }

    // ── Screen-off timestamp ────────────────────────────────────────

    fun saveScreenOffTimestamp(context: Context) {
        prefs(context).edit { putLong(LAST_SCREEN_OFF_KEY, System.currentTimeMillis()) }
    }

    fun getScreenOffTimestamp(context: Context): Long =
        prefs(context).getLong(LAST_SCREEN_OFF_KEY, 0L)

    // ── Timer-running flag (for ScreenUnlockReceiver) ───────────────

    fun setTimerRunning(context: Context, running: Boolean) {
        prefs(context).edit { putBoolean(TIMER_RUNNING_KEY, running) }
    }

    fun isTimerRunning(context: Context): Boolean =
        prefs(context).getBoolean(TIMER_RUNNING_KEY, false)

    // ── Quick-launch bypass session ─────────────────────────────────

    fun startQuickLaunchSession(context: Context, allowedPackages: Set<String>) {
        val serialized = allowedPackages
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(",")
        prefs(context).edit {
            putBoolean(QUICK_LAUNCH_SESSION_ACTIVE_KEY, true)
            putString(QUICK_LAUNCH_PACKAGES_KEY, serialized)
        }
    }

    fun clearQuickLaunchSession(context: Context) {
        prefs(context).edit {
            putBoolean(QUICK_LAUNCH_SESSION_ACTIVE_KEY, false)
            remove(QUICK_LAUNCH_PACKAGES_KEY)
        }
    }

    fun isQuickLaunchSessionActive(context: Context): Boolean =
        prefs(context).getBoolean(QUICK_LAUNCH_SESSION_ACTIVE_KEY, false)

    fun getQuickLaunchPackages(context: Context): Set<String> {
        val raw = prefs(context).getString(QUICK_LAUNCH_PACKAGES_KEY, "") ?: ""
        if (raw.isBlank()) return emptySet()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}
