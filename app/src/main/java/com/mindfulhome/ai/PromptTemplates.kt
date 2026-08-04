package com.mindfulhome.ai

import android.content.Context
import com.mindfulhome.locale.AppLanguage
import com.mindfulhome.settings.SettingsManager

/**
 * Default and user-editable AI prompts. Gate context templates use `{placeholders}` and `[[optional blocks]]`.
 *
 * @see docs.gates.md Syntax, placeholders, and gate behavior.
 */
object PromptTemplates {

    const val GENERAL_CHAT_GREETING = "Hi! What do you want to do with your time?"

    /** Appended to every system prompt so model replies match the in-app language. */
    fun replyLanguageInstruction(language: AppLanguage): String =
        "Always write your replies in ${language.englishName} (locale ${language.tag})."

    fun withReplyLanguage(context: Context, systemPrompt: String): String {
        val language = SettingsManager.getAppLanguage(context)
        val instruction = replyLanguageInstruction(language)
        return if (systemPrompt.contains(instruction)) {
            systemPrompt
        } else {
            "$systemPrompt\n\n$instruction"
        }
    }

    val DEFAULT_FOCUS_GATE_SYSTEM_PROMPT = """
        The user is in a focus-time window and wants to spend phone time now.
        Your job is to verify that their stated intent is legitimate and intentional.
        You do NOT care which app they might use later, and you must NOT launch or discuss specific apps.
        Call grantTimeAccess only when you are genuinely satisfied with their answers.
        Never grant before the minimum round count in the user context.
        One sentence replies only. Be casual and friendly.
        Follow the round policy provided in the user context.
    """.trimIndent()

    val DEFAULT_GATEKEEPER_SYSTEM_PROMPT = """
        The user wants to open a hidden app. You open it by calling grantAccess.
        One sentence replies only. Be casual and friendly.

        Ask why they need it and gently push for intentional use.
        Call grantAccess only when you are genuinely satisfied with their reason.
        Never grant before the minimum round count in the user context.
        If you need more context, you may call queryRecentUsageSessions(limit) to inspect recent behavior before deciding.
        If the user context includes confrontation evidence, your first reply must confront them with that exact evidence first.
        Follow the round policy provided in the user context.
    """.trimIndent()

    val DEFAULT_FOCUS_GATE_CONTEXT_TEMPLATE = """
        Focus time is active ({focusWindowDescription}).
        User set a {durationMinutes} minute session. [[Declared intent: "{declaredIntent}". ]]
        Verify whether spending phone time now is intentional and aligned with that intent.
        Do not ask about or reference specific apps.
        Do NOT call grantTimeAccess before round {minRounds}.
        Only call grantTimeAccess when you are genuinely satisfied — never grant before round {minRounds}.
    """.trimIndent()

    val DEFAULT_GATEKEEPER_CONTEXT_TEMPLATE = """
        User wants to open {appName} (karma {karmaScore}, opened {totalOpens} times, overran {totalOverruns} times, requested today {timesRequestedToday}).
        [[The user has this to say about the app: "{appNote}". ]][[{cautionGate}That note contains cautionary language — take it seriously and push back before granting. ]]Focus mode active: {focusModeActive}.
        [[Recent usage evidence: {confrontationBrief} ]]Do NOT call grantAccess before round {minRounds}.
        Only call grantAccess when you are genuinely satisfied — never grant before round {minRounds}.
    """.trimIndent()

    const val CONTEXT_TEMPLATE_SYNTAX_HELP =
        "Use {name} for values. Optional text: [[...]] is omitted when any {name} inside it is empty (not set)."

    val GATEKEEPER_CONTEXT_PLACEHOLDERS =
        "{appName}, {karmaScore}, {totalOpens}, {totalOverruns}, {timesRequestedToday}, {minRounds}, " +
            "{focusModeActive}, {appNote} (per-app note from Karma), {confrontationBrief} (usage from last timer), " +
            "{cautionGate} (auto: non-empty when the note matches caution keywords — use only to gate a [[...]] block)"

    val FOCUS_GATE_CONTEXT_PLACEHOLDERS =
        "{durationMinutes}, {declaredIntent}, {focusWindowDescription}, {minRounds}"

    fun focusGateSystemPrompt(context: Context): String =
        withReplyLanguage(context, SettingsManager.getFocusGateSystemPromptResolved(context))

    fun gatekeeperSystemPrompt(context: Context): String =
        withReplyLanguage(context, SettingsManager.getGatekeeperSystemPromptResolved(context))

    fun nudgeSystemPrompt(context: Context): String = withReplyLanguage(
        context,
        """
        The user's timer expired. Gently suggest wrapping up. One sentence only.
        
        If they want more time and give a reason, call grantExtension(minutes).
        Do not block them. You are just a friendly nudge.
        """.trimIndent(),
    )

    fun generalChatSystemPrompt(
        context: Context,
        hiddenAppsBriefing: String,
        appNotesBriefing: String?,
        installedAppsBriefing: String? = null,
    ): String = withReplyLanguage(
        context,
        buildString {
            appendLine("Use tools to control actions: launchApp(packageName), suggestApps(query), presentSuggestions(query). One sentence replies only.")
            appendLine()
            appendLine(hiddenAppsBriefing)
            if (!installedAppsBriefing.isNullOrBlank()) {
                appendLine()
                appendLine(installedAppsBriefing)
            }
            if (!appNotesBriefing.isNullOrBlank()) {
                appendLine(appNotesBriefing)
            }
            appendLine()
            appendLine("Hidden app → say \"[name] has been overused. What do you need it for?\" then after they answer call launchApp.")
            appendLine("Other app with high confidence → call launchApp immediately.")
            appendLine("If a candidate app has a worrying note, ask one extra confirmation turn before launchApp.")
            appendLine("Low confidence or ambiguous request -> call suggestApps with a short search query.")
            appendLine("After suggestApps, you will receive ranked candidates with scores in a follow-up message.")
            appendLine("Then choose exactly one path:")
            appendLine("1) Confident -> call launchApp(packageName).")
            appendLine("2) Need user pick -> call presentSuggestions(query).")
            appendLine("3) Need clarification -> do not call any launch/suggestion tool and continue conversation.")
            appendLine("When candidate notes/flags are provided in suggestApps results, treat them as constraints.")
            appendLine("Risky note or needs_extra_confirmation=true -> ask one more confirmation turn or push back before launchApp.")
            append("Never ask for exact package names.")
        },
    )

    fun buildFocusGateUserContext(
        context: Context,
        durationMinutes: Int,
        declaredIntent: String,
        focusWindowDescription: String,
        minRoundsBeforeGrant: Int,
    ): String {
        val template = SettingsManager.getFocusGateContextTemplateResolved(context)
        return applyTemplate(
            template,
            mapOf(
                "durationMinutes" to durationMinutes.toString(),
                "declaredIntent" to declaredIntent.trim(),
                "focusWindowDescription" to focusWindowDescription,
                "minRounds" to minRoundsBeforeGrant.toString(),
            ),
        )
    }

    fun buildGatekeeperUserContext(
        context: Context,
        appName: String,
        karmaScore: Int,
        totalOpens: Int,
        totalOverruns: Int,
        timesRequestedToday: Int,
        minRoundsBeforeGrant: Int,
        focusModeActive: Boolean,
        appNote: String?,
        requiresExtraConfirmation: Boolean,
        confrontationBrief: String? = null,
    ): String {
        val trimmedNote = appNote?.trim().orEmpty()
        val template = SettingsManager.getGatekeeperContextTemplateResolved(context)
        return applyTemplate(
            template,
            mapOf(
                "appName" to appName,
                "karmaScore" to karmaScore.toString(),
                "totalOpens" to totalOpens.toString(),
                "totalOverruns" to totalOverruns.toString(),
                "timesRequestedToday" to timesRequestedToday.toString(),
                "minRounds" to minRoundsBeforeGrant.toString(),
                "focusModeActive" to focusModeActive.toString(),
                "appNote" to trimmedNote,
                "confrontationBrief" to confrontationBrief?.trim().orEmpty(),
                "cautionGate" to if (requiresExtraConfirmation && trimmedNote.isNotBlank()) " " else "",
            ),
        )
    }

    internal fun applyTemplate(template: String, values: Map<String, String>): String {
        var result = resolveOptionalBlocks(template, values)
        for ((key, value) in values) {
            result = result.replace("{$key}", value)
        }
        return result
            .replace(Regex("[ \t]{2,}"), " ")
            .replace(Regex(" ?\n ?"), "\n")
            .trim()
    }

    private fun resolveOptionalBlocks(template: String, values: Map<String, String>): String {
        val pattern = Regex("\\[\\[(.*?)\\]\\]", RegexOption.DOT_MATCHES_ALL)
        return pattern.replace(template) { match ->
            val inner = match.groupValues[1]
            val keys = Regex("\\{([^}]+)\\}").findAll(inner).map { it.groupValues[1] }.toSet()
            val include = keys.all { key -> !values[key].isNullOrEmpty() }
            if (include) inner else ""
        }
    }

    fun requiresExtraConfirmation(note: String?): Boolean {
        val normalized = note?.trim()?.lowercase().orEmpty()
        if (normalized.isBlank()) return false
        val worryingKeywords = listOf(
            "don't open", "do not open", "avoid", "relapse", "addict", "doomscroll", "doom scroll",
            "waste", "time sink", "danger", "harm", "anxiety", "panic", "spiral", "trigger",
            "toxic", "urgent only", "work only", "study only", "exam", "sleep", "bedtime",
        )
        return worryingKeywords.any { normalized.contains(it) }
    }

    fun riskConfirmationPrompt(appNote: String?): String {
        val safeNote = appNote?.trim().orEmpty()
        return if (safeNote.isBlank()) {
            "One more quick check before I open it - do you still want to proceed?"
        } else {
            "Your note for this app says \"$safeNote\" - still want to open it?"
        }
    }

    fun buildNudgeContext(
        appName: String,
        karmaScore: Int,
        overrunMinutes: Int,
        nudgeCount: Int
    ): String =
        "Timer expired $overrunMinutes min ago on $appName (karma $karmaScore). Nudge #${nudgeCount + 1}."

    fun fallbackFocusGateResponse(
        durationMinutes: Int,
        declaredIntent: String,
        exchangeCount: Int,
    ): String {
        val intentSuffix = declaredIntent.trim().takeIf { it.isNotBlank() }
            ?.let { " You said: \"$it\"." }
            .orEmpty()
        return when (exchangeCount) {
            0 -> "It's focus time, and you're starting a $durationMinutes minute session.$intentSuffix Is this really how you want to spend it?"
            1 -> "Got it. What would intentional use of this window look like for you?"
            else -> "If you're still sure, tap Proceed when you're ready — use the time mindfully."
        }
    }

    fun fallbackGatekeeperResponse(
        appName: String,
        exchangeCount: Int,
        confrontationBrief: String? = null,
    ): String {
        return when {
            exchangeCount == 0 && !confrontationBrief.isNullOrBlank() ->
                "Before we open $appName: $confrontationBrief What's your concrete reason for opening it now?"
            exchangeCount == 0 ->
                "Hey, you're about to open $appName. It's been a bit of a time sink lately. What do you need it for right now?"
            exchangeCount == 1 -> "I hear you. What would intentional use look like for the next few minutes?"
            else -> "I still want you to be deliberate about this — if you're sure, tap Proceed when you're ready."
        }
    }

    /** Whether the offline fallback should allow access at this completed round count. */
    fun fallbackShouldGrantAccess(exchangeCount: Int): Boolean = exchangeCount >= 2

    fun fallbackNudgeResponse(appName: String, nudgeCount: Int): String {
        return when {
            nudgeCount <= 1 -> "Your time is up. Ready to wrap up with $appName?"
            nudgeCount <= 3 -> "Still on $appName - just checking in."
            else -> "You've been over your limit for a while. No pressure, but your $appName karma is taking a hit."
        }
    }
}
