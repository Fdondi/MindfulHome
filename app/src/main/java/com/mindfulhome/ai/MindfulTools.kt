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

    fun reset() {
        launchedPackage = ""
    }

    @Tool(description = "Launch an app on the user's phone. Use the exact package name from the hidden apps briefing, or a well-known Android package name.")
    fun launchApp(
        @ToolParam(description = "The Android package name of the app, e.g. com.instagram.android") packageName: String
    ): Map<String, Any> {
        launchedPackage = packageName
        return mapOf("status" to "launching", "package" to packageName)
    }
}
