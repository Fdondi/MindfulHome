package com.mindfulhome.ai

import com.mindfulhome.ai.backend.BackendClient
import com.mindfulhome.data.UsageSession
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.ln

/** Result of pruning timestamps and checking the reply rate limit. */
data class RateLimitEvaluation(
    val allowed: Boolean,
    val waitSec: Long,
    /** Timestamps still inside the window (and, when [allowed], including now). */
    val timestamps: List<Long>,
)

data class GatekeeperRoundBudget(
    val minRounds: Int,
    val maxRounds: Int,
)

/**
 * Pure helpers extracted from [NegotiationManager] for unit testing and CRAP reduction.
 */
object NegotiationManagerLogic {

    /**
     * Prunes [timestamps] older than [windowMs] and decides whether another message
     * at [nowMs] is allowed under a sliding-window cap of [maxMessages].
     */
    fun evaluateRateLimit(
        timestamps: List<Long>,
        nowMs: Long,
        maxMessages: Int = 10,
        windowMs: Long = 60_000L,
    ): RateLimitEvaluation {
        val pruned = timestamps.filter { nowMs - it <= windowMs }
        if (pruned.size >= maxMessages) {
            val oldest = pruned.first()
            val waitSec = (windowMs - (nowMs - oldest)) / 1000 + 1
            return RateLimitEvaluation(allowed = false, waitSec = waitSec, timestamps = pruned)
        }
        return RateLimitEvaluation(
            allowed = true,
            waitSec = 0,
            timestamps = pruned + nowMs,
        )
    }

    /** Computes min/max gatekeeper rounds from karma and risk/focus bonuses. */
    fun computeGatekeeperRoundBudget(
        negativeKarma: Int,
        extraRiskConfirmation: Boolean,
        focusModeActive: Boolean,
    ): GatekeeperRoundBudget {
        val baseMinRounds = ceil(ln(1.0 + negativeKarma.coerceAtLeast(0).toDouble())).toInt()
        val riskBonus = if (extraRiskConfirmation) 1 else 0
        val focusRoundsBonus = if (focusModeActive) 1 else 0
        val minRounds = (baseMinRounds + focusRoundsBonus + riskBonus).coerceAtLeast(1)
        val maxRounds = (minRounds * 2).coerceAtLeast(minRounds)
        return GatekeeperRoundBudget(minRounds = minRounds, maxRounds = maxRounds)
    }

    /**
     * Enforces min-rounds-before-grant and auto-grant at max rounds for gatekeeper
     * and focus-gate flows. Other negotiation types pass through unchanged.
     */
    fun applyGatekeeperRoundPolicy(
        result: NegotiationResult,
        negotiationType: NegotiationType?,
        exchangeCount: Int,
        minRounds: Int,
        maxRounds: Int,
    ): NegotiationResult {
        if (negotiationType != NegotiationType.GATEKEEPER &&
            negotiationType != NegotiationType.FOCUS_GATE
        ) {
            return result
        }

        val effectiveMin = minRounds.coerceAtLeast(0)
        val effectiveMax = maxRounds.coerceAtLeast(effectiveMin)
        val isFocusGate = negotiationType == NegotiationType.FOCUS_GATE

        if (exchangeCount < effectiveMin) {
            if (!result.accessGranted) return result
            return result.copy(
                responseText = rewriteEarlyGrantResponse(result.responseText, isFocusGate),
                accessGranted = false,
            )
        }

        if (exchangeCount >= effectiveMax && !result.accessGranted) {
            return result.copy(
                responseText = result.responseText +
                    if (isFocusGate) {
                        "\n\nAlright, you've stayed with this. Go use your time mindfully."
                    } else {
                        "\n\nAlright, you've stayed with this. Go ahead."
                    },
                accessGranted = true,
            )
        }

        return result
    }

    fun looksLikeOpenPermission(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.contains('?')) return false
        val lower = trimmed.lowercase()
        val phrases = listOf(
            "go ahead",
            "you can proceed",
            "granted",
            "go for it",
            "you're good",
            "you are good",
            "i'll let you",
            "i will let you",
            "enjoy your",
        )
        return phrases.any { lower.contains(it) }
    }

    fun rewriteEarlyGrantResponse(text: String, isFocusGate: Boolean): String {
        if (!looksLikeOpenPermission(text)) return text
        return if (isFocusGate) {
            "Can this wait until focus time ends, or is there a real deadline?"
        } else {
            "What's the concrete reason you need this right now?"
        }
    }

    /**
     * Maps backend text + function calls into a [NegotiationResult].
     *
     * [usageHistorySummary] is invoked for `queryRecentUsageSessions` so callers
     * can inject a fixture without hitting the repository.
     */
    fun parseBackendResult(
        text: String,
        functionCalls: List<BackendClient.FunctionCall>,
        @Suppress("UNUSED_PARAMETER") negotiationType: NegotiationType?,
        usageHistorySummary: (limit: Int) -> String = { "No usage history available." },
    ): NegotiationResult {
        for (fc in functionCalls) {
            when (fc.name) {
                "grantAccess" -> return NegotiationResult(
                    responseText = text.ifBlank { "Opening the app for you." },
                    accessGranted = true,
                )
                "grantTimeAccess" -> return NegotiationResult(
                    responseText = text.ifBlank { "Go ahead — use your time mindfully." },
                    accessGranted = true,
                )
                "grantExtension" -> {
                    val minutes = fc.args["minutes"]?.jsonPrimitive?.int ?: 10
                    return NegotiationResult(
                        responseText = text.ifBlank { "Extending your time by $minutes minutes." },
                        extensionMinutes = minutes,
                        accessGranted = true,
                    )
                }
                "launchApp" -> {
                    val pkg = (fc.args["packageName"] as? JsonPrimitive)?.content ?: ""
                    return NegotiationResult(
                        responseText = text.ifBlank { "Launching the app." },
                        launchedPackage = pkg,
                    )
                }
                "suggestApps" -> {
                    val query = (fc.args["query"] as? JsonPrimitive)?.content.orEmpty().trim()
                    return NegotiationResult(
                        responseText = text.ifBlank { "Here are the closest app options." },
                        suggestedQuery = query,
                    )
                }
                "presentSuggestions" -> {
                    val query = (fc.args["query"] as? JsonPrimitive)?.content.orEmpty().trim()
                    return NegotiationResult(
                        responseText = text.ifBlank { "Pick one of these options." },
                        suggestedQuery = query,
                        showSuggestions = true,
                    )
                }
                "queryRecentUsageSessions" -> {
                    val limit = fc.args["limit"]?.jsonPrimitive?.int ?: 5
                    val summary = usageHistorySummary(limit)
                    return NegotiationResult(
                        responseText = if (text.isBlank()) summary else "$text\n\n$summary",
                    )
                }
            }
        }
        return NegotiationResult(responseText = text)
    }

    /** Formats recent usage sessions for the gatekeeper tool response. */
    fun buildUsageHistorySummary(
        packageName: String,
        sessions: List<UsageSession>,
        limit: Int,
    ): String {
        if (packageName.isBlank()) {
            return "I don't have a target app context yet, so I can't query usage history."
        }

        val safeLimit = limit.coerceIn(1, 20)
        val clipped = sessions.take(safeLimit)
        if (clipped.isEmpty()) {
            return "No previous usage sessions were found for this app."
        }

        val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        return buildString {
            append("Most recent ")
            append(clipped.size)
            append(" usage sessions:\n")
            clipped.forEachIndexed { index, session ->
                val started = formatter.format(Date(session.startTimestamp))
                val ended = session.endTimestamp?.let { formatter.format(Date(it)) } ?: "ongoing"
                val timerMinutes = msToMinutes(session.timerDurationMs)
                val overrunMinutes = msToMinutes(session.overrunMs)
                val outcome = when {
                    session.endTimestamp == null -> "in progress"
                    session.closedOnTime -> "closed on time"
                    session.overrunMs > 0 -> "overran by ${overrunMinutes}m"
                    else -> "ended"
                }
                append(
                    "${index + 1}) $started -> $ended, timer ${timerMinutes}m, $outcome, " +
                        "karma ${session.karmaChange}\n",
                )
            }
        }.trimEnd()
    }

    fun msToMinutes(ms: Long): Long {
        if (ms <= 0L) return 0L
        return (ms + 59_999L) / 60_000L
    }

    /**
     * Simulates the backend reply mapping used after a successful generate call:
     * parse function calls, then apply gatekeeper round policy.
     */
    fun mapBackendReply(
        text: String,
        functionCalls: List<BackendClient.FunctionCall>,
        negotiationType: NegotiationType?,
        exchangeCount: Int,
        minRounds: Int,
        maxRounds: Int,
        usageHistorySummary: (limit: Int) -> String = { "No usage history available." },
    ): NegotiationResult {
        val parsed = parseBackendResult(
            text = text,
            functionCalls = functionCalls,
            negotiationType = negotiationType,
            usageHistorySummary = usageHistorySummary,
        )
        return applyGatekeeperRoundPolicy(
            result = parsed,
            negotiationType = negotiationType,
            exchangeCount = exchangeCount,
            minRounds = minRounds,
            maxRounds = maxRounds,
        )
    }

    fun buildHiddenAppsBriefing(
        lines: List<String>,
    ): String {
        if (lines.isEmpty()) return "No apps are currently hidden."
        return "Currently hidden apps:\n" + lines.joinToString("\n")
    }

    fun formatHiddenAppBriefingLine(
        label: String,
        packageName: String,
        karmaScore: Int,
        note: String?,
    ): String {
        val noteSuffix = note?.trim()?.takeIf { it.isNotBlank() }?.let { ", note: \"$it\"" }.orEmpty()
        return "- $label ($packageName), karma: $karmaScore$noteSuffix"
    }

    fun buildAppNotesBriefing(lines: List<String>): String? =
        lines.takeIf { it.isNotEmpty() }?.let { "App notes:\n${it.joinToString("\n")}" }

    fun formatAppNoteBriefingLine(
        label: String,
        packageName: String,
        note: String,
        needsExtraConfirmation: Boolean,
    ): String = "- $label ($packageName): \"$note\" (needs extra confirmation: $needsExtraConfirmation)"

    fun buildInstalledAppsBriefing(lines: List<String>): String? =
        lines.takeIf { it.isNotEmpty() }?.let { "Installed apps available to launch:\n${it.joinToString("\n")}" }

    fun formatInstalledAppBriefingLine(label: String, packageName: String): String =
        "- $label ($packageName)"

    fun mergeSystemPromptWithDailySummaries(
        basePrompt: String,
        dailySummariesBriefing: String?,
    ): String {
        if (dailySummariesBriefing.isNullOrBlank()) return basePrompt
        return buildString {
            appendLine(basePrompt)
            appendLine()
            appendLine(dailySummariesBriefing)
        }.trim()
    }

    fun alreadySaidOpeningInstruction(opening: String): String =
        "You already opened with: \"$opening\". Continue from there; do not repeat that opening."

    fun mergeSystemPromptWithOpening(
        systemPrompt: String,
        opening: String,
        userContext: String = "",
    ): String {
        val withContext = if (userContext.isBlank()) systemPrompt else "$systemPrompt\n\n$userContext"
        return "$withContext\n\n${alreadySaidOpeningInstruction(opening)}"
    }

    /** First gate message is always scripted; the model is not invoked yet. */
    fun scriptedGateOpeningResult(openingText: String): NegotiationResult =
        NegotiationResult(responseText = openingText, accessGranted = false)

    fun formatDailySummariesBriefing(dayToSummary: List<Pair<String, String>>): String? {
        if (dayToSummary.isEmpty()) return null
        val body = dayToSummary.joinToString(separator = "\n\n") { (day, summary) ->
            "### $day\n${summary.trim()}"
        }
        return "Recent daily log summaries (most recent first):\n$body"
    }

    /** Whether gatekeeper/focus-gate exchanges should increment before a reply path. */
    fun shouldIncrementExchangeBeforeReply(type: NegotiationType?): Boolean =
        type == NegotiationType.GATEKEEPER || type == NegotiationType.FOCUS_GATE

    fun modelNotFoundMessage(backendModel: String): String =
        "Model '$backendModel' is not available. Please go to Settings and pick a different model."

    fun isModelNotFoundCode(code: String?): Boolean = code == "model_not_found"

    /**
     * Formats a single tool-call params log line, or null when the tool name is unrecognized.
     */
    fun formatParsedToolParamsLine(
        name: String,
        args: Map<String, kotlinx.serialization.json.JsonElement>,
    ): String? {
        val prim = { key: String -> (args[key] as? JsonPrimitive)?.content }
        return when (name) {
            "grantExtension" -> "tool params parsed: grantExtension(minutes=${prim("minutes")})"
            "launchApp", "suggestApps", "presentSuggestions" ->
                formatQuotedToolParamLine(name, prim)
            "queryRecentUsageSessions" -> {
                val limit = prim("limit")?.toIntOrNull() ?: 5
                "tool params parsed: queryRecentUsageSessions(limit=$limit)"
            }
            else -> null
        }
    }

    private fun formatQuotedToolParamLine(
        name: String,
        prim: (String) -> String?,
    ): String {
        val key = if (name == "launchApp") "packageName" else "query"
        val value = quoteForLog(prim(key).orEmpty())
        return "tool params parsed: $name($key=$value)"
    }

    private fun quoteForLog(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun formatToolDeclarationNames(tools: List<Map<String, kotlinx.serialization.json.JsonElement>>): String {
        val names = mutableListOf<String>()
        tools.forEach { tool ->
            val decls = tool["functionDeclarations"] as? kotlinx.serialization.json.JsonArray
            decls?.forEach { decl ->
                val name = (decl as? kotlinx.serialization.json.JsonObject)?.get("name")?.let {
                    (it as? JsonPrimitive)?.content
                }
                if (name != null) names.add(name)
            }
        }
        return "[${names.joinToString(", ")}]"
    }

    fun parseOnDeviceResult(
        response: String,
        type: NegotiationType?,
        gatekeeperGranted: Boolean,
        focusGateGranted: Boolean,
        nudgeExtensionMinutes: Int,
        launchedPackage: String,
        suggestedQuery: String,
        showSuggestions: Boolean,
    ): NegotiationResult = when (type) {
        NegotiationType.GATEKEEPER -> NegotiationResult(
            responseText = response,
            accessGranted = gatekeeperGranted,
        )
        NegotiationType.FOCUS_GATE -> NegotiationResult(
            responseText = response,
            accessGranted = focusGateGranted,
        )
        NegotiationType.NUDGE -> NegotiationResult(
            responseText = response,
            extensionMinutes = nudgeExtensionMinutes,
            accessGranted = nudgeExtensionMinutes > 0,
        )
        NegotiationType.GENERAL -> NegotiationResult(
            responseText = response,
            launchedPackage = launchedPackage,
            suggestedQuery = suggestedQuery,
            showSuggestions = showSuggestions,
        )
        null -> NegotiationResult(response)
    }
}
