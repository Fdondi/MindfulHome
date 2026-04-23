package com.mindfulhome.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.mindfulhome.service.UsageTracker
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

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

    // Focus time windows (AI-first mode)
    private const val FOCUS_TIME_ENABLED_KEY = "focus_time_enabled"
    private const val FOCUS_TIME_INTERVALS_KEY = "focus_time_intervals"

    // Escalation threshold (number of nudge cycles before forcing back to timer)
    private const val ESCALATION_THRESHOLD_KEY = "escalation_nudge_threshold"
    const val DEFAULT_ESCALATION_THRESHOLD = 5
    const val MIN_ESCALATION_THRESHOLD = 2
    const val MAX_ESCALATION_THRESHOLD = 15

    // Nudge stage timing and escalation
    private const val NUDGE_INITIAL_NOTIFICATION_DELAY_MINUTES_KEY = "nudge_initial_notification_delay_minutes"
    private const val NUDGE_BUBBLE_INTERVAL_SECONDS_KEY = "nudge_bubble_interval_seconds"
    private const val NUDGE_BUBBLES_BEFORE_BANNER_KEY = "nudge_bubbles_before_banner"
    private const val NUDGE_BANNER_INTERVAL_MINUTES_KEY = "nudge_banner_interval_minutes"
    private const val NUDGE_TYPING_IDLE_TIMEOUT_MINUTES_KEY = "nudge_typing_idle_timeout_minutes"
    private const val NUDGE_INTERACTION_WATCH_TIMEOUT_MINUTES_KEY = "nudge_interaction_watch_timeout_minutes"

    const val DEFAULT_NUDGE_INITIAL_NOTIFICATION_DELAY_MINUTES = 1
    const val MIN_NUDGE_INITIAL_NOTIFICATION_DELAY_MINUTES = 0
    const val MAX_NUDGE_INITIAL_NOTIFICATION_DELAY_MINUTES = 5

    const val DEFAULT_NUDGE_BUBBLE_INTERVAL_SECONDS = 20
    const val MIN_NUDGE_BUBBLE_INTERVAL_SECONDS = 5
    const val MAX_NUDGE_BUBBLE_INTERVAL_SECONDS = 60

    const val DEFAULT_NUDGE_BUBBLES_BEFORE_BANNER = 10
    const val MIN_NUDGE_BUBBLES_BEFORE_BANNER = 1
    const val MAX_NUDGE_BUBBLES_BEFORE_BANNER = 30

    const val DEFAULT_NUDGE_BANNER_INTERVAL_MINUTES = 1
    const val MIN_NUDGE_BANNER_INTERVAL_MINUTES = 1
    const val MAX_NUDGE_BANNER_INTERVAL_MINUTES = 10

    const val DEFAULT_NUDGE_TYPING_IDLE_TIMEOUT_MINUTES = 1
    const val MIN_NUDGE_TYPING_IDLE_TIMEOUT_MINUTES = 1
    const val MAX_NUDGE_TYPING_IDLE_TIMEOUT_MINUTES = 10

    const val DEFAULT_NUDGE_INTERACTION_WATCH_TIMEOUT_MINUTES = 1
    const val MIN_NUDGE_INTERACTION_WATCH_TIMEOUT_MINUTES = 1
    const val MAX_NUDGE_INTERACTION_WATCH_TIMEOUT_MINUTES = 10

    // Karma hide threshold (number of bad-karma points before the app is hidden)
    private const val HIDE_THRESHOLD_KEY = "karma_hide_threshold"
    private const val LAST_KARMA_RECOVERY_EPOCH_DAY_KEY = "last_karma_recovery_epoch_day"
    const val DEFAULT_HIDE_THRESHOLD = 2
    const val MIN_HIDE_THRESHOLD = 0
    const val MAX_HIDE_THRESHOLD = 10

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
    private const val LAST_TIMER_USAGE_SNAPSHOT_KEY = "last_timer_usage_snapshot_json"

    // Permission prompt suppression (user explicitly skipped prompts)
    private const val SUPPRESS_NOTIFICATIONS_PROMPT_KEY = "suppress_notifications_prompt"
    private const val SUPPRESS_USAGE_ACCESS_PROMPT_KEY = "suppress_usage_access_prompt"
    private const val SUPPRESS_OVERLAY_PROMPT_KEY = "suppress_overlay_prompt"
    private const val NUDGE_BANNER_FALLBACK_ARMED_KEY = "nudge_banner_fallback_armed"
    private const val DEVELOPER_LOGS_ENABLED_KEY = "developer_logs_enabled"

    /** Custom instructions for daily log summarization (version bumps when saved). */
    private const val DAILY_SUMMARY_PROMPT_TEXT_KEY = "daily_summary_prompt_text"
    private const val DAILY_SUMMARY_PROMPT_VERSION_KEY = "daily_summary_prompt_version"

    /**
     * Default summarization instructions (editable in Settings). JSON shape is enforced in code.
     */
    val DEFAULT_DAILY_SUMMARY_PROMPT_TEXT = """
You are generating an end-of-day summary for the user's phone usage sessions.
Highlight what's interesting or notable about what happened today.
Be concise, with 3-7 bullet points max, and one short concluding sentence.
""".trimIndent()

    const val MIN_DAILY_SUMMARY_REGENERATE = 0
    const val MAX_DAILY_SUMMARY_REGENERATE = 30

    /** Available Vertex AI models the user can pick from. */
    data class ModelOption(val id: String, val label: String, val description: String)

    /** Fallback list used when the backend is unreachable. */
    val AVAILABLE_MODELS = listOf(
        ModelOption("gemini-2.5-flash-lite", "🍃 Gemini 2.5 Flash Lite", "Fastest, lowest cost"),
        ModelOption("gemini-2.5-flash", "⭐ Gemini 2.5 Flash", "Fast and smart"),
        ModelOption("gemini-2.5-pro", "💎 Gemini 2.5 Pro", "Smartest, with thinking, high cost"),
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

    // ── Permission prompt suppression ────────────────────────────────

    private fun permissionPromptKey(permissionPrompt: PermissionPrompt): String = when (permissionPrompt) {
        PermissionPrompt.NOTIFICATIONS -> SUPPRESS_NOTIFICATIONS_PROMPT_KEY
        PermissionPrompt.USAGE_ACCESS -> SUPPRESS_USAGE_ACCESS_PROMPT_KEY
        PermissionPrompt.OVERLAY -> SUPPRESS_OVERLAY_PROMPT_KEY
    }

    fun isPermissionPromptSuppressed(context: Context, permissionPrompt: PermissionPrompt): Boolean =
        prefs(context).getBoolean(permissionPromptKey(permissionPrompt), false)

    fun setPermissionPromptSuppressed(
        context: Context,
        permissionPrompt: PermissionPrompt,
        suppressed: Boolean,
    ) {
        prefs(context).edit {
            putBoolean(permissionPromptKey(permissionPrompt), suppressed)
        }
    }

    fun isNudgeBannerFallbackArmed(context: Context): Boolean =
        prefs(context).getBoolean(NUDGE_BANNER_FALLBACK_ARMED_KEY, false)

    fun setNudgeBannerFallbackArmed(context: Context, armed: Boolean) {
        prefs(context).edit {
            putBoolean(NUDGE_BANNER_FALLBACK_ARMED_KEY, armed)
        }
    }

    fun isDeveloperLogsEnabled(context: Context): Boolean =
        prefs(context).getBoolean(DEVELOPER_LOGS_ENABLED_KEY, false)

    fun setDeveloperLogsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit {
            putBoolean(DEVELOPER_LOGS_ENABLED_KEY, enabled)
        }
    }

    // ── Daily log summary prompt ───────────────────────────────────────

    /**
     * Version of the summarization prompt. `0` means only defaults have been used (nothing saved in Settings).
     * Increments each time the user saves new instructions.
     */
    fun getDailySummaryPromptVersion(context: Context): Int =
        prefs(context).getInt(DAILY_SUMMARY_PROMPT_VERSION_KEY, 0)

    /**
     * Text shown in Settings and sent to the model (falls back to [DEFAULT_DAILY_SUMMARY_PROMPT_TEXT] when version is 0).
     */
    fun getDailySummaryPromptTextForEditing(context: Context): String {
        val stored = prefs(context).getString(DAILY_SUMMARY_PROMPT_TEXT_KEY, null)
        if (!stored.isNullOrBlank()) return stored
        return DEFAULT_DAILY_SUMMARY_PROMPT_TEXT
    }

    /** Resolved instructions for generation (default when version 0). */
    fun getDailySummaryPromptTextResolved(context: Context): String {
        if (getDailySummaryPromptVersion(context) == 0) {
            return DEFAULT_DAILY_SUMMARY_PROMPT_TEXT
        }
        val stored = prefs(context).getString(DAILY_SUMMARY_PROMPT_TEXT_KEY, "") ?: ""
        return stored.trim().ifBlank { DEFAULT_DAILY_SUMMARY_PROMPT_TEXT }
    }

    /**
     * Saves new instructions, bumps the prompt version, and returns the new version number.
     */
    fun saveDailySummaryPromptText(context: Context, text: String): Int {
        val p = prefs(context)
        val newVersion = p.getInt(DAILY_SUMMARY_PROMPT_VERSION_KEY, 0) + 1
        p.edit {
            putString(DAILY_SUMMARY_PROMPT_TEXT_KEY, text.trim())
            putInt(DAILY_SUMMARY_PROMPT_VERSION_KEY, newVersion)
        }
        return newVersion
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
    data class LastTimerUsageApp(
        val packageName: String,
        val foregroundTimeMs: Long,
        val longestSessionsMsDesc: List<Long>,
    )
    data class LastTimerUsageSnapshot(
        val capturedAtMs: Long,
        val topApps: List<LastTimerUsageApp>,
    )
    data class FocusInterval(
        val startMinutes: Int,
        val endMinutes: Int,
    )

    enum class PermissionPrompt {
        NOTIFICATIONS,
        USAGE_ACCESS,
        OVERLAY,
    }

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

    // ── Last timer usage snapshot ───────────────────────────────────

    fun saveLastTimerUsageSnapshot(
        context: Context,
        capturedAtMs: Long,
        topApps: List<UsageTracker.DailyAppUsage>,
    ) {
        if (capturedAtMs <= 0L || topApps.isEmpty()) {
            clearLastTimerUsageSnapshot(context)
            return
        }

        val appsJson = JSONArray()
        topApps.forEach { usage ->
            val chunksJson = JSONArray()
            usage.timeChunksMsDesc.forEach { chunksJson.put(it) }
            val appJson = JSONObject()
                .put("packageName", usage.packageName)
                .put("foregroundTimeMs", usage.foregroundTimeMs)
                .put("longestSessionsMsDesc", chunksJson)
            appsJson.put(appJson)
        }

        val payload = JSONObject()
            .put("capturedAtMs", capturedAtMs)
            .put("topApps", appsJson)
            .toString()
        prefs(context).edit { putString(LAST_TIMER_USAGE_SNAPSHOT_KEY, payload) }
    }

    fun getLastTimerUsageSnapshot(context: Context): LastTimerUsageSnapshot? {
        val raw = prefs(context).getString(LAST_TIMER_USAGE_SNAPSHOT_KEY, null) ?: return null
        return try {
            val payload = JSONObject(raw)
            val capturedAtMs = payload.optLong("capturedAtMs", 0L)
            if (capturedAtMs <= 0L) return null
            val appsJson = payload.optJSONArray("topApps") ?: JSONArray()
            val apps = buildList {
                for (i in 0 until appsJson.length()) {
                    val appJson = appsJson.optJSONObject(i) ?: continue
                    val packageName = appJson.optString("packageName", "").trim()
                    val foregroundTimeMs = appJson.optLong("foregroundTimeMs", 0L)
                    if (packageName.isBlank() || foregroundTimeMs <= 0L) continue
                    val chunksJson = appJson.optJSONArray("longestSessionsMsDesc") ?: JSONArray()
                    val chunks = buildList {
                        for (j in 0 until chunksJson.length()) {
                            val value = chunksJson.optLong(j, 0L)
                            if (value > 0L) add(value)
                        }
                    }
                    add(
                        LastTimerUsageApp(
                            packageName = packageName,
                            foregroundTimeMs = foregroundTimeMs,
                            longestSessionsMsDesc = chunks,
                        )
                    )
                }
            }
            if (apps.isEmpty()) return null
            LastTimerUsageSnapshot(capturedAtMs = capturedAtMs, topApps = apps)
        } catch (_: Exception) {
            null
        }
    }

    fun clearLastTimerUsageSnapshot(context: Context) {
        prefs(context).edit {
            remove(LAST_TIMER_USAGE_SNAPSHOT_KEY)
        }
    }

    // ── Quick return window ─────────────────────────────────────────

    fun getQuickReturnMinutes(context: Context): Int =
        prefs(context).getInt(QUICK_RETURN_THRESHOLD_KEY, DEFAULT_QUICK_RETURN_MINUTES)

    fun setQuickReturnMinutes(context: Context, minutes: Int) {
        prefs(context).edit {
            putInt(QUICK_RETURN_THRESHOLD_KEY, minutes.coerceIn(MIN_QUICK_RETURN_MINUTES, MAX_QUICK_RETURN_MINUTES))
        }
    }

    // ── Focus time windows ───────────────────────────────────────────

    fun isFocusTimeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(FOCUS_TIME_ENABLED_KEY, false)

    fun setFocusTimeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(FOCUS_TIME_ENABLED_KEY, enabled) }
    }

    fun getFocusTimeIntervals(context: Context): List<FocusInterval> {
        val raw = prefs(context).getString(FOCUS_TIME_INTERVALS_KEY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",")
            .mapNotNull { token ->
                val parts = token.split("-")
                if (parts.size != 2) return@mapNotNull null
                val start = parts[0].toIntOrNull() ?: return@mapNotNull null
                val end = parts[1].toIntOrNull() ?: return@mapNotNull null
                FocusInterval(
                    startMinutes = start.coerceIn(0, MINUTES_PER_DAY - 1),
                    endMinutes = end.coerceIn(0, MINUTES_PER_DAY - 1),
                )
            }
    }

    fun setFocusTimeIntervals(context: Context, intervals: List<FocusInterval>) {
        val serialized = intervals
            .map { interval ->
                val start = interval.startMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
                val end = interval.endMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
                "$start-$end"
            }
            .joinToString(",")
        prefs(context).edit { putString(FOCUS_TIME_INTERVALS_KEY, serialized) }
    }

    fun isFocusTimeActiveNow(
        context: Context,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!isFocusTimeEnabled(context)) return false
        val intervals = getFocusTimeIntervals(context)
        if (intervals.isEmpty()) return false
        val minuteOfDay = minuteOfDay(nowMs)
        return intervals.any { interval ->
            isMinuteWithinInterval(
                minuteOfDay = minuteOfDay,
                startMinutes = interval.startMinutes,
                endMinutes = interval.endMinutes,
            )
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

    // ── Nudge stage timing and escalation ───────────────────────────

    fun getNudgeInitialNotificationDelayMinutes(context: Context): Int =
        prefs(context).getInt(
            NUDGE_INITIAL_NOTIFICATION_DELAY_MINUTES_KEY,
            DEFAULT_NUDGE_INITIAL_NOTIFICATION_DELAY_MINUTES,
        )

    fun setNudgeInitialNotificationDelayMinutes(context: Context, value: Int) {
        prefs(context).edit {
            putInt(
                NUDGE_INITIAL_NOTIFICATION_DELAY_MINUTES_KEY,
                value.coerceIn(
                    MIN_NUDGE_INITIAL_NOTIFICATION_DELAY_MINUTES,
                    MAX_NUDGE_INITIAL_NOTIFICATION_DELAY_MINUTES,
                ),
            )
        }
    }

    fun getNudgeBubbleIntervalSeconds(context: Context): Int =
        prefs(context).getInt(
            NUDGE_BUBBLE_INTERVAL_SECONDS_KEY,
            DEFAULT_NUDGE_BUBBLE_INTERVAL_SECONDS,
        )

    fun setNudgeBubbleIntervalSeconds(context: Context, value: Int) {
        prefs(context).edit {
            putInt(
                NUDGE_BUBBLE_INTERVAL_SECONDS_KEY,
                value.coerceIn(
                    MIN_NUDGE_BUBBLE_INTERVAL_SECONDS,
                    MAX_NUDGE_BUBBLE_INTERVAL_SECONDS,
                ),
            )
        }
    }

    fun getNudgeBubblesBeforeBanner(context: Context): Int =
        prefs(context).getInt(
            NUDGE_BUBBLES_BEFORE_BANNER_KEY,
            DEFAULT_NUDGE_BUBBLES_BEFORE_BANNER,
        )

    fun setNudgeBubblesBeforeBanner(context: Context, value: Int) {
        prefs(context).edit {
            putInt(
                NUDGE_BUBBLES_BEFORE_BANNER_KEY,
                value.coerceIn(
                    MIN_NUDGE_BUBBLES_BEFORE_BANNER,
                    MAX_NUDGE_BUBBLES_BEFORE_BANNER,
                ),
            )
        }
    }

    fun getNudgeBannerIntervalMinutes(context: Context): Int =
        prefs(context).getInt(
            NUDGE_BANNER_INTERVAL_MINUTES_KEY,
            DEFAULT_NUDGE_BANNER_INTERVAL_MINUTES,
        )

    fun setNudgeBannerIntervalMinutes(context: Context, value: Int) {
        prefs(context).edit {
            putInt(
                NUDGE_BANNER_INTERVAL_MINUTES_KEY,
                value.coerceIn(
                    MIN_NUDGE_BANNER_INTERVAL_MINUTES,
                    MAX_NUDGE_BANNER_INTERVAL_MINUTES,
                ),
            )
        }
    }

    fun getNudgeTypingIdleTimeoutMinutes(context: Context): Int =
        prefs(context).getInt(
            NUDGE_TYPING_IDLE_TIMEOUT_MINUTES_KEY,
            DEFAULT_NUDGE_TYPING_IDLE_TIMEOUT_MINUTES,
        )

    fun setNudgeTypingIdleTimeoutMinutes(context: Context, value: Int) {
        prefs(context).edit {
            putInt(
                NUDGE_TYPING_IDLE_TIMEOUT_MINUTES_KEY,
                value.coerceIn(
                    MIN_NUDGE_TYPING_IDLE_TIMEOUT_MINUTES,
                    MAX_NUDGE_TYPING_IDLE_TIMEOUT_MINUTES,
                ),
            )
        }
    }

    fun getNudgeInteractionWatchTimeoutMinutes(context: Context): Int =
        prefs(context).getInt(
            NUDGE_INTERACTION_WATCH_TIMEOUT_MINUTES_KEY,
            DEFAULT_NUDGE_INTERACTION_WATCH_TIMEOUT_MINUTES,
        )

    fun setNudgeInteractionWatchTimeoutMinutes(context: Context, value: Int) {
        prefs(context).edit {
            putInt(
                NUDGE_INTERACTION_WATCH_TIMEOUT_MINUTES_KEY,
                value.coerceIn(
                    MIN_NUDGE_INTERACTION_WATCH_TIMEOUT_MINUTES,
                    MAX_NUDGE_INTERACTION_WATCH_TIMEOUT_MINUTES,
                ),
            )
        }
    }

    // ── Karma hide threshold ────────────────────────────────────────

    fun getHideThreshold(context: Context): Int =
        prefs(context).getInt(HIDE_THRESHOLD_KEY, DEFAULT_HIDE_THRESHOLD)

    fun setHideThreshold(context: Context, threshold: Int) {
        prefs(context).edit {
            putInt(HIDE_THRESHOLD_KEY, threshold.coerceIn(MIN_HIDE_THRESHOLD, MAX_HIDE_THRESHOLD))
        }
    }

    fun getLastKarmaRecoveryEpochDay(context: Context): Long =
        prefs(context).getLong(LAST_KARMA_RECOVERY_EPOCH_DAY_KEY, -1L)

    fun setLastKarmaRecoveryEpochDay(context: Context, epochDay: Long) {
        prefs(context).edit {
            putLong(LAST_KARMA_RECOVERY_EPOCH_DAY_KEY, epochDay)
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

    private fun minuteOfDay(nowMs: Long): Int {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = nowMs
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        return hour * 60 + minute
    }

    private fun isMinuteWithinInterval(
        minuteOfDay: Int,
        startMinutes: Int,
        endMinutes: Int,
    ): Boolean {
        val start = startMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
        val end = endMinutes.coerceIn(0, MINUTES_PER_DAY - 1)
        if (start == end) return true
        return if (start < end) {
            minuteOfDay in start until end
        } else {
            minuteOfDay >= start || minuteOfDay < end
        }
    }

    private const val MINUTES_PER_DAY = 24 * 60
}
