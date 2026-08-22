package com.mindfulhome.ai

import com.druk.lmplayground.api.model.ToolDefinition

/**
 * Tools executed in this process when LM Playground returns `tool_calls`.
 * After [LmClient.sendMessage] returns, callers read the side-effect flags.
 */
interface LocalLmToolSet {
    fun definitions(): List<ToolDefinition>
    suspend fun invoke(name: String, argumentsJson: String): String
    fun reset()
}

/**
 * Tools the gatekeeper AI can call to grant access to a hidden app.
 */
class GatekeeperTools : LocalLmToolSet {

    var accessGranted = false
        private set
    var lastUsageHistorySummary: String = ""
        private set

    private var usageHistoryResolver: suspend (Int) -> String = { "No usage history available." }

    override fun reset() {
        accessGranted = false
        lastUsageHistorySummary = ""
    }

    fun setUsageHistoryResolver(resolver: suspend (Int) -> String) {
        usageHistoryResolver = resolver
    }

    override fun definitions(): List<ToolDefinition> = listOf(
        LocalLmToolLogic.tool(
            name = "grantAccess",
            description = "Open the hidden app for the user. Call this when you decide to let them use it.",
        ),
        LocalLmToolLogic.tool(
            name = "queryRecentUsageSessions",
            description = "Query the most recent app-use sessions before deciding whether to grant access.",
            parametersSchema = LocalLmToolLogic.intPropertySchema(
                "limit",
                "How many recent sessions to fetch, from 1 to 20",
            ),
        ),
    )

    override suspend fun invoke(name: String, argumentsJson: String): String = when (name) {
        "grantAccess" -> grantAccess()
        "queryRecentUsageSessions" -> queryRecentUsageSessions(
            LocalLmToolLogic.intArg(argumentsJson, "limit", 5),
        )
        else -> LocalLmToolLogic.unknownToolResult(name)
    }

    private fun grantAccess(): String {
        accessGranted = true
        return LocalLmToolLogic.objectResult(
            "status" to "launched",
            "message" to "The app is now opening.",
        )
    }

    private suspend fun queryRecentUsageSessions(limit: Int): String {
        val safeLimit = limit.coerceIn(1, 20)
        val summary = usageHistoryResolver(safeLimit).ifBlank {
            "No usage history available."
        }
        lastUsageHistorySummary = summary
        return LocalLmToolLogic.objectResult(
            "status" to "ok",
            "limit" to safeLimit,
            "summary" to summary,
        )
    }
}

/**
 * Tools the focus-time gate AI can call to let the user proceed with their session.
 */
class FocusGateTools : LocalLmToolSet {

    var accessGranted = false
        private set

    override fun reset() {
        accessGranted = false
    }

    override fun definitions(): List<ToolDefinition> = listOf(
        LocalLmToolLogic.tool(
            name = "grantTimeAccess",
            description = "Allow the user to proceed with their timed session during focus time.",
        ),
    )

    override suspend fun invoke(name: String, argumentsJson: String): String = when (name) {
        "grantTimeAccess" -> {
            accessGranted = true
            LocalLmToolLogic.objectResult(
                "status" to "granted",
                "message" to "The user may proceed to their session.",
            )
        }
        else -> LocalLmToolLogic.unknownToolResult(name)
    }
}

/**
 * Tools the nudge AI can call to grant a timer extension.
 */
class NudgeTools : LocalLmToolSet {

    var extensionMinutes = 0
        private set

    override fun reset() {
        extensionMinutes = 0
    }

    override fun definitions(): List<ToolDefinition> = listOf(
        LocalLmToolLogic.tool(
            name = "grantExtension",
            description = "Grant the user extra time on their current app. Call this when they give a good reason for needing more time.",
            parametersSchema = LocalLmToolLogic.intPropertySchema(
                "minutes",
                "Number of extra minutes to grant, typically 5 to 15",
            ),
        ),
    )

    override suspend fun invoke(name: String, argumentsJson: String): String {
        if (name != "grantExtension") return LocalLmToolLogic.unknownToolResult(name)
        val minutes = LocalLmToolLogic.intArg(argumentsJson, "minutes", 10)
        extensionMinutes = minutes
        return LocalLmToolLogic.objectResult("status" to "extended", "minutes" to minutes)
    }
}

/**
 * Tools the general-chat AI can call to launch any app.
 */
class GeneralChatTools : LocalLmToolSet {

    var launchedPackage: String = ""
        private set
    var suggestedQuery: String = ""
        private set
    var showSuggestions: Boolean = false
        private set

    override fun reset() {
        launchedPackage = ""
        suggestedQuery = ""
        showSuggestions = false
    }

    override fun definitions(): List<ToolDefinition> = listOf(
        LocalLmToolLogic.tool(
            name = "launchApp",
            description = "Launch an app on the user's phone. Use the exact package name from the hidden apps briefing, or a well-known Android package name.",
            parametersSchema = LocalLmToolLogic.stringPropertySchema(
                "packageName",
                "The Android package name of the app, e.g. com.instagram.android",
            ),
        ),
        LocalLmToolLogic.tool(
            name = "suggestApps",
            description = "Request app suggestions when you are not confident about one exact package to launch.",
            parametersSchema = LocalLmToolLogic.stringPropertySchema(
                "query",
                "Search query to rank suggested apps, e.g. 'music', 'maps', 'work chat'",
            ),
        ),
        LocalLmToolLogic.tool(
            name = "presentSuggestions",
            description = "Show ranked app options to the user instead of launching immediately.",
            parametersSchema = LocalLmToolLogic.stringPropertySchema(
                "query",
                "Optional query label for the ranked options being presented",
            ),
        ),
    )

    override suspend fun invoke(name: String, argumentsJson: String): String = when (name) {
        "launchApp" -> launchApp(LocalLmToolLogic.stringArg(argumentsJson, "packageName"))
        "suggestApps" -> suggestApps(LocalLmToolLogic.stringArg(argumentsJson, "query"))
        "presentSuggestions" -> presentSuggestions(LocalLmToolLogic.stringArg(argumentsJson, "query"))
        else -> LocalLmToolLogic.unknownToolResult(name)
    }

    private fun launchApp(packageName: String): String {
        launchedPackage = packageName
        return LocalLmToolLogic.objectResult("status" to "launching", "package" to packageName)
    }

    private fun suggestApps(query: String): String {
        suggestedQuery = query.trim()
        showSuggestions = false
        return LocalLmToolLogic.objectResult("status" to "suggesting", "query" to suggestedQuery)
    }

    private fun presentSuggestions(query: String): String {
        suggestedQuery = query.trim()
        showSuggestions = true
        return LocalLmToolLogic.objectResult("status" to "presenting", "query" to suggestedQuery)
    }
}
