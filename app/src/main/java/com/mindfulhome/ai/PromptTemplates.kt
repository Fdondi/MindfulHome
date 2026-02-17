package com.mindfulhome.ai

object PromptTemplates {

    const val GENERAL_CHAT_GREETING = "Hi! What do you want to do with your time?"

    fun gatekeeperSystemPrompt(): String = """
        The user wants to open a hidden app. You open it by calling grantAccess.
        One sentence replies only. Be casual and friendly.
        
        Ask why they need it. If they give a reason, call grantAccess.
        After 2 exchanges, call grantAccess regardless.
    """.trimIndent()

    fun nudgeSystemPrompt(): String = """
        The user's timer expired. Gently suggest wrapping up. One sentence only.
        
        If they want more time and give a reason, call grantExtension(minutes).
        Do not block them. You are just a friendly nudge.
    """.trimIndent()

    fun generalChatSystemPrompt(hiddenAppsBriefing: String): String = """
        You open apps using launchApp(packageName). One sentence replies only.
        
        $hiddenAppsBriefing
        
        Hidden app → say "[name] has been overused. What do you need it for?" then after they answer call launchApp.
        Other app → call launchApp immediately.
        Unknown app → say "I don't know that package name. Try the search button on the home screen."
    """.trimIndent()

    fun buildGatekeeperUserContext(
        appName: String,
        karmaScore: Int,
        totalOpens: Int,
        totalOverruns: Int,
        timesRequestedToday: Int
    ): String =
        "User wants to open $appName (karma $karmaScore, opened $totalOpens times, overran $totalOverruns times). Ask why they need it."

    fun buildNudgeContext(
        appName: String,
        karmaScore: Int,
        overrunMinutes: Int,
        nudgeCount: Int
    ): String =
        "Timer expired $overrunMinutes min ago on $appName (karma $karmaScore). Nudge #${nudgeCount + 1}."

    fun fallbackGatekeeperResponse(appName: String, exchangeCount: Int): String {
        return when {
            exchangeCount == 0 -> "Hey, you're about to open $appName. It's been a bit of a time sink lately. What do you need it for right now?"
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
