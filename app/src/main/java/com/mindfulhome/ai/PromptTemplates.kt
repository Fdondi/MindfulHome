package com.mindfulhome.ai

object PromptTemplates {

    const val GENERAL_CHAT_GREETING = "Hi! What do you want to do with your time?"

    fun gatekeeperSystemPrompt(): String = """
        The user wants to open a hidden app. You open it by calling grantAccess.
        One sentence replies only. Be casual and friendly.

        Ask why they need it and gently push for intentional use.
        If you need more context, you may call queryRecentUsageSessions(limit) to inspect recent behavior before deciding.
        If the user context includes confrontation evidence, your first reply must confront them with that exact evidence first.
        Follow the round-window policy provided in the user context.
    """.trimIndent()

    fun nudgeSystemPrompt(): String = """
        The user's timer expired. Gently suggest wrapping up. One sentence only.
        
        If they want more time and give a reason, call grantExtension(minutes).
        Do not block them. You are just a friendly nudge.
    """.trimIndent()

    fun generalChatSystemPrompt(
        hiddenAppsBriefing: String,
        appNotesBriefing: String?,
        installedAppsBriefing: String? = null,
    ): String = buildString {
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
    }

    fun buildGatekeeperUserContext(
        appName: String,
        karmaScore: Int,
        totalOpens: Int,
        totalOverruns: Int,
        timesRequestedToday: Int,
        minRoundsBeforeGrant: Int,
        maxRoundsBeforeGrant: Int,
        focusModeActive: Boolean,
        appNote: String?,
        requiresExtraConfirmation: Boolean,
        confrontationBrief: String? = null,
    ): String =
        "User wants to open $appName (karma $karmaScore, opened $totalOpens times, " +
            "overran $totalOverruns times, requested today $timesRequestedToday). " +
            appNote?.trim()?.takeIf { it.isNotBlank() }?.let { "App note: \"$it\". " }.orEmpty() +
            "Worrying note flag: $requiresExtraConfirmation. " +
            "Focus mode active: $focusModeActive. " +
            confrontationBrief?.trim()?.takeIf { it.isNotBlank() }?.let {
                "Confrontation evidence: $it "
            }.orEmpty() +
            "Do NOT call grantAccess before round $minRoundsBeforeGrant. " +
            "Call grantAccess by round $maxRoundsBeforeGrant at the latest."

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
            exchangeCount == 1 -> "I hear you. Just want to make sure you're being intentional about it. Still want to go ahead?"
            else -> "Alright, go ahead. Just try to keep it mindful!"
        }
    }

    /** Whether the fallback at this exchange count should grant access. */
    fun fallbackShouldGrantAccess(exchangeCount: Int): Boolean = exchangeCount >= 2

    fun fallbackNudgeResponse(appName: String, nudgeCount: Int): String {
        return when {
            nudgeCount <= 1 -> "Your time is up. Ready to wrap up with $appName?"
            nudgeCount <= 3 -> "Still on $appName - just checking in."
            else -> "You've been over your limit for a while. No pressure, but your $appName karma is taking a hit."
        }
    }
}
