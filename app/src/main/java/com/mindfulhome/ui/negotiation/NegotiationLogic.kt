package com.mindfulhome.ui.negotiation

import com.mindfulhome.ai.NegotiationResult
import com.mindfulhome.ai.PromptTemplates
import com.mindfulhome.model.AppInfo

data class NegotiationMode(
    val isFocusGate: Boolean,
    val isExtendGate: Boolean,
    val isGateFlow: Boolean,
)

sealed class GateOutcome {
    data object NoChange : GateOutcome()
    data class Granted(val extensionMinutes: Int = 0) : GateOutcome()
}

sealed class LaunchStrategy {
    data class DirectLaunch(val packageName: String) : LaunchStrategy()
    data class ShowChooser(val query: String) : LaunchStrategy()
    data object ContinueChat : LaunchStrategy()
    data object None : LaunchStrategy()
}

internal fun normalizeLookup(value: String): String {
    return value.lowercase().replace(Regex("[^a-z0-9]"), "")
}

internal fun extractLaunchQuery(rawText: String): String {
    val trimmed = rawText.trim()
    if (trimmed.isBlank()) return ""
    val quoted = Regex("\"([^\"]+)\"|'([^']+)'").find(trimmed)
    if (quoted != null) {
        val capture = quoted.groupValues.drop(1).firstOrNull { it.isNotBlank() }
        if (!capture.isNullOrBlank()) return capture.trim()
    }
    val lowered = trimmed.lowercase()
    val markers = listOf("app name is", "app is", "open", "launch")
    for (marker in markers) {
        val idx = lowered.lastIndexOf(marker)
        if (idx >= 0) {
            val candidate = trimmed.substring(idx + marker.length).trim()
            if (candidate.isNotBlank()) return candidate
        }
    }
    return trimmed
}

internal fun classifyNegotiationMode(
    packageName: String,
    extendGate: Boolean,
    focusModeActive: Boolean,
): NegotiationMode {
    val isFocusGate = !extendGate && packageName.isEmpty() && focusModeActive
    val isExtendGate = extendGate && packageName.isNotEmpty()
    val isGateFlow = packageName.isNotEmpty() || isFocusGate || isExtendGate
    return NegotiationMode(
        isFocusGate = isFocusGate,
        isExtendGate = isExtendGate,
        isGateFlow = isGateFlow,
    )
}

internal fun applyGateOutcome(
    mode: NegotiationMode,
    result: NegotiationResult,
): GateOutcome {
    if (mode.isExtendGate) {
        return if (result.extensionMinutes > 0) {
            GateOutcome.Granted(extensionMinutes = result.extensionMinutes)
        } else {
            GateOutcome.NoChange
        }
    }
    return if (result.accessGranted) {
        GateOutcome.Granted()
    } else {
        GateOutcome.NoChange
    }
}

/**
 * Mirrors NegotiationScreen launch decisions after a chat result
 * (general-chat first reply and send-button path).
 */
internal fun decideLaunchStrategy(
    result: NegotiationResult,
    mode: NegotiationMode,
    packageName: String,
    fallbackQuery: String = "",
): LaunchStrategy {
    if (mode.isFocusGate || mode.isExtendGate) return LaunchStrategy.None
    if (result.launchedPackage.isNotEmpty()) {
        return LaunchStrategy.DirectLaunch(result.launchedPackage)
    }
    if (packageName.isEmpty() && result.showSuggestions) {
        return LaunchStrategy.ShowChooser(
            result.suggestedQuery.ifBlank { fallbackQuery },
        )
    }
    if (packageName.isEmpty()) {
        return LaunchStrategy.ContinueChat
    }
    return LaunchStrategy.None
}

internal fun findExactMatchPackage(
    queryText: String,
    apps: List<AppInfo>,
): String? {
    val normalizedQuery = normalizeLookup(queryText)
    if (normalizedQuery.isBlank()) return null
    return apps.firstOrNull { app ->
        val normalizedLabel = normalizeLookup(app.label)
        val normalizedPackage = normalizeLookup(app.packageName)
        val normalizedShortPackage = normalizeLookup(app.packageName.substringAfterLast('.'))
        normalizedLabel == normalizedQuery ||
            normalizedPackage == normalizedQuery ||
            normalizedShortPackage == normalizedQuery
    }?.packageName
}

internal fun buildSuggestAppsToolMessage(
    suggestedQuery: String,
    ranked: List<AppInfo>,
    notesByPackage: Map<String, String>,
): String {
    if (ranked.isEmpty()) {
        return "Tool result for suggestApps(query=\"$suggestedQuery\"):\n" +
            "No apps matched this query. Try a different search query with suggestApps, " +
            "or ask the user for clarification."
    }
    val candidates = ranked.take(5)
    return buildString {
        append("Tool result for suggestApps(query=\"")
        append(suggestedQuery)
        append("\"):\n")
        candidates.forEachIndexed { index, app ->
            val appNote = notesByPackage[app.packageName].orEmpty()
            val noteFlag = if (PromptTemplates.requiresExtraConfirmation(appNote)) "true" else "false"
            append(index + 1)
            append(". ")
            append(app.label)
            append(" (")
            append(app.packageName)
            append(")")
            if (appNote.isNotBlank()) {
                append(" note=\"")
                append(appNote.replace("\"", "\\\""))
                append("\"")
            }
            append(" needs_extra_confirmation=")
            append(noteFlag)
            append('\n')
        }
        append("Choose exactly one next action:\n")
        append("- launchApp(packageName) if confident.\n")
        append("- presentSuggestions(query) to show options.\n")
        append("- no tool call if you want to continue conversation.\n")
        append("You MUST consider app notes and needs_extra_confirmation flags before deciding. ")
        append("If a note indicates risk/avoidance, do not launch immediately; ask for confirmation or push back first.")
    }
}
