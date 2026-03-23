package com.mindfulhome.ai

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * Tools the gatekeeper AI can call to grant access to a hidden app.
 *
 * When the model calls [grantAccess], LiteRT-LM executes it automatically
 * and feeds the result back. After sendMessage returns, the caller checks
 * [accessGranted] to know whether the app should be launched.
 */
class GatekeeperTools : ToolSet {

    var accessGranted = false
        private set
    var lastUsageHistorySummary: String = ""
        private set

    private var usageHistoryResolver: (Int) -> String = { "No usage history available." }

    fun reset() {
        accessGranted = false
        lastUsageHistorySummary = ""
    }

    fun setUsageHistoryResolver(resolver: (Int) -> String) {
        usageHistoryResolver = resolver
    }

    @Tool(description = "Open the hidden app for the user. Call this when you decide to let them use it.")
    fun grantAccess(): Map<String, Any> {
        accessGranted = true
        return mapOf("status" to "launched", "message" to "The app is now opening.")
    }

    @Tool(description = "Query the most recent app-use sessions before deciding whether to grant access.")
    fun queryRecentUsageSessions(
        @ToolParam(description = "How many recent sessions to fetch, from 1 to 20") limit: Int
    ): Map<String, Any> {
        val safeLimit = limit.coerceIn(1, 20)
        val summary = usageHistoryResolver(safeLimit).ifBlank {
            "No usage history available."
        }
        lastUsageHistorySummary = summary
        return mapOf(
            "status" to "ok",
            "limit" to safeLimit,
            "summary" to summary,
        )
    }
}

/**
 * Tools the nudge AI can call to grant a timer extension.
 *
 * When the model calls [grantExtension], the caller checks
 * [extensionMinutes] after sendMessage returns.
 */
class NudgeTools : ToolSet {

    var extensionMinutes = 0
        private set

    fun reset() {
        extensionMinutes = 0
    }

    @Tool(description = "Grant the user extra time on their current app. Call this when they give a good reason for needing more time.")
    fun grantExtension(
        @ToolParam(description = "Number of extra minutes to grant, typically 5 to 15") minutes: Int
    ): Map<String, Any> {
        extensionMinutes = minutes
        return mapOf("status" to "extended", "minutes" to minutes)
    }
}

/**
 * Tools the general-chat AI can call to launch any app.
 *
 * The hidden apps briefing gives the AI the package names it needs.
 * For well-known apps the model can also use its general knowledge.
 */
class GeneralChatTools : ToolSet {

    var launchedPackage: String = ""
        private set
    var suggestedQuery: String = ""
        private set
    var showSuggestions: Boolean = false
        private set

    fun reset() {
        launchedPackage = ""
        suggestedQuery = ""
        showSuggestions = false
    }

    @Tool(description = "Launch an app on the user's phone. Use the exact package name from the hidden apps briefing, or a well-known Android package name.")
    fun launchApp(
        @ToolParam(description = "The Android package name of the app, e.g. com.instagram.android") packageName: String
    ): Map<String, Any> {
        launchedPackage = packageName
        return mapOf("status" to "launching", "package" to packageName)
    }

    @Tool(description = "Request app suggestions when you are not confident about one exact package to launch.")
    fun suggestApps(
        @ToolParam(description = "Search query to rank suggested apps, e.g. 'music', 'maps', 'work chat'") query: String
    ): Map<String, Any> {
        suggestedQuery = query.trim()
        showSuggestions = false
        return mapOf("status" to "suggesting", "query" to suggestedQuery)
    }

    @Tool(description = "Show ranked app options to the user instead of launching immediately.")
    fun presentSuggestions(
        @ToolParam(description = "Optional query label for the ranked options being presented") query: String
    ): Map<String, Any> {
        suggestedQuery = query.trim()
        showSuggestions = true
        return mapOf("status" to "presenting", "query" to suggestedQuery)
    }
}
