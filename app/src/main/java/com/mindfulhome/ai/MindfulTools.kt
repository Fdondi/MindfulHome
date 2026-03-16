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

    fun reset() {
        accessGranted = false
    }

    @Tool(description = "Open the hidden app for the user. Call this when you decide to let them use it.")
    fun grantAccess(): Map<String, Any> {
        accessGranted = true
        return mapOf("status" to "launched", "message" to "The app is now opening.")
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
    var lastSearchSummary: String = ""
        private set

    private var searchResolver: (String) -> List<Pair<String, String>> = { emptyList() }
    private var searchObserver: (String, List<Pair<String, String>>) -> Unit = { _, _ -> }

    fun reset() {
        launchedPackage = ""
        lastSearchSummary = ""
    }

    fun setSearchResolver(resolver: (String) -> List<Pair<String, String>>) {
        searchResolver = resolver
    }

    fun setSearchObserver(observer: (String, List<Pair<String, String>>) -> Unit) {
        searchObserver = observer
    }

    @Tool(description = "Launch an app on the user's phone. Use the exact package name from the hidden apps briefing, or a well-known Android package name.")
    fun launchApp(
        @ToolParam(description = "The Android package name of the app, e.g. com.instagram.android") packageName: String
    ): Map<String, Any> {
        launchedPackage = packageName
        return mapOf("status" to "launching", "package" to packageName)
    }

    @Tool(description = "Search installed apps by name and return matching app labels with package names. Use this when launchApp fails or package is uncertain.")
    fun searchApps(
        @ToolParam(description = "App name or keyword to search for, e.g. instagram, maps, spotify") query: String
    ): Map<String, Any> {
        val matches = searchResolver(query.trim())
        searchObserver(query, matches)
        lastSearchSummary = if (matches.isEmpty()) {
            "No matching installed apps found for '$query'."
        } else {
            buildString {
                append("I found these installed matches:\n")
                matches.forEachIndexed { index, (label, pkg) ->
                    append("${index + 1}) $label ($pkg)\n")
                }
                append("Reply with the number, like 1 or 2.")
            }.trimEnd()
        }
        return mapOf(
            "status" to "results",
            "count" to matches.size,
            "matches" to lastSearchSummary,
        )
    }
}
