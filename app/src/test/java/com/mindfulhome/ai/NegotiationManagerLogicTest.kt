package com.mindfulhome.ai

import com.mindfulhome.ai.backend.BackendClient
import com.mindfulhome.data.UsageSession
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NegotiationManagerLogicTest {

    // ── Rate limit ───────────────────────────────────────────────────

    @Test
    fun evaluateRateLimit_allowsUnderCap_andAppendsNow() {
        val now = 100_000L
        val result = NegotiationManagerLogic.evaluateRateLimit(
            timestamps = listOf(now - 10_000, now - 5_000),
            nowMs = now,
            maxMessages = 10,
            windowMs = 60_000L,
        )
        assertTrue(result.allowed)
        assertEquals(0L, result.waitSec)
        assertEquals(listOf(now - 10_000, now - 5_000, now), result.timestamps)
    }

    @Test
    fun evaluateRateLimit_prunesOutsideWindow() {
        val now = 100_000L
        val result = NegotiationManagerLogic.evaluateRateLimit(
            timestamps = listOf(now - 90_000, now - 1_000),
            nowMs = now,
            maxMessages = 10,
            windowMs = 60_000L,
        )
        assertTrue(result.allowed)
        assertEquals(listOf(now - 1_000, now), result.timestamps)
    }

    @Test
    fun evaluateRateLimit_blocksAtCap_withWaitSec() {
        val now = 100_000L
        val oldest = now - 10_000
        val stamps = (0 until 10).map { oldest + it }
        val result = NegotiationManagerLogic.evaluateRateLimit(
            timestamps = stamps,
            nowMs = now,
            maxMessages = 10,
            windowMs = 60_000L,
        )
        assertFalse(result.allowed)
        // waitSec = (60000 - 10000) / 1000 + 1 = 51
        assertEquals(51L, result.waitSec)
        assertEquals(stamps, result.timestamps)
    }

    // ── Round budget ─────────────────────────────────────────────────

    @Test
    fun computeGatekeeperRoundBudget_zeroKarma_minIsOne() {
        val budget = NegotiationManagerLogic.computeGatekeeperRoundBudget(
            negativeKarma = 0,
            extraRiskConfirmation = false,
            focusModeActive = false,
        )
        assertEquals(1, budget.minRounds)
        assertEquals(2, budget.maxRounds)
    }

    @Test
    fun computeGatekeeperRoundBudget_addsRiskAndFocusBonuses() {
        // ceil(ln(1+20)) = ceil(3.04) = 4, +1 focus +1 risk → 6; max = 12
        val budget = NegotiationManagerLogic.computeGatekeeperRoundBudget(
            negativeKarma = 20,
            extraRiskConfirmation = true,
            focusModeActive = true,
        )
        assertEquals(6, budget.minRounds)
        assertEquals(12, budget.maxRounds)
    }

    // ── Round policy ─────────────────────────────────────────────────

    @Test
    fun applyGatekeeperRoundPolicy_blocksGrantBeforeMinRounds() {
        val result = NegotiationManagerLogic.applyGatekeeperRoundPolicy(
            result = NegotiationResult("ok", accessGranted = true),
            negotiationType = NegotiationType.GATEKEEPER,
            exchangeCount = 1,
            minRounds = 3,
            maxRounds = 6,
        )
        assertFalse(result.accessGranted)
        assertEquals("ok", result.responseText)
        assertFalse(result.responseText.contains("round", ignoreCase = true))
        assertFalse(result.responseText.contains("turn", ignoreCase = true))
    }

    @Test
    fun applyGatekeeperRoundPolicy_rewritesPermissionWithoutMentioningTurns() {
        val result = NegotiationManagerLogic.applyGatekeeperRoundPolicy(
            result = NegotiationResult("Go ahead.", accessGranted = true),
            negotiationType = NegotiationType.FOCUS_GATE,
            exchangeCount = 0,
            minRounds = 2,
            maxRounds = 4,
        )
        assertFalse(result.accessGranted)
        assertEquals(
            "Can this wait until focus time ends, or is there a real deadline?",
            result.responseText,
        )
        assertFalse(result.responseText.contains("round", ignoreCase = true))
        assertFalse(result.responseText.contains("turn", ignoreCase = true))
    }

    @Test
    fun applyGatekeeperRoundPolicy_autoGrantsAtMaxRounds() {
        val result = NegotiationManagerLogic.applyGatekeeperRoundPolicy(
            result = NegotiationResult("still thinking", accessGranted = false),
            negotiationType = NegotiationType.FOCUS_GATE,
            exchangeCount = 4,
            minRounds = 2,
            maxRounds = 4,
        )
        assertTrue(result.accessGranted)
        assertTrue(result.responseText.contains("Go use your time mindfully."))
    }

    @Test
    fun applyGatekeeperRoundPolicy_passthroughForNudge() {
        val input = NegotiationResult("extend?", extensionMinutes = 5, accessGranted = true)
        val result = NegotiationManagerLogic.applyGatekeeperRoundPolicy(
            result = input,
            negotiationType = NegotiationType.NUDGE,
            exchangeCount = 0,
            minRounds = 3,
            maxRounds = 6,
        )
        assertEquals(input, result)
    }

    // ── parseBackendResult ───────────────────────────────────────────

    @Test
    fun parseBackendResult_grantAccess() {
        val result = NegotiationManagerLogic.parseBackendResult(
            text = "",
            functionCalls = listOf(BackendClient.FunctionCall("grantAccess")),
            negotiationType = NegotiationType.GATEKEEPER,
        )
        assertTrue(result.accessGranted)
        assertEquals("Opening the app for you.", result.responseText)
    }

    @Test
    fun parseBackendResult_grantExtension_readsMinutes() {
        val result = NegotiationManagerLogic.parseBackendResult(
            text = "Sure.",
            functionCalls = listOf(
                BackendClient.FunctionCall(
                    name = "grantExtension",
                    args = mapOf("minutes" to JsonPrimitive(15)),
                ),
            ),
            negotiationType = NegotiationType.NUDGE,
        )
        assertTrue(result.accessGranted)
        assertEquals(15, result.extensionMinutes)
        assertEquals("Sure.", result.responseText)
    }

    @Test
    fun parseBackendResult_launchApp_readsPackage() {
        val result = NegotiationManagerLogic.parseBackendResult(
            text = "Launching.",
            functionCalls = listOf(
                BackendClient.FunctionCall(
                    name = "launchApp",
                    args = mapOf("packageName" to JsonPrimitive("com.example.app")),
                ),
            ),
            negotiationType = NegotiationType.GENERAL,
        )
        assertEquals("com.example.app", result.launchedPackage)
    }

    @Test
    fun parseBackendResult_queryRecentUsageSessions_injectsSummary() {
        val result = NegotiationManagerLogic.parseBackendResult(
            text = "Checking history.",
            functionCalls = listOf(
                BackendClient.FunctionCall(
                    name = "queryRecentUsageSessions",
                    args = mapOf("limit" to JsonPrimitive(3)),
                ),
            ),
            negotiationType = NegotiationType.GATEKEEPER,
            usageHistorySummary = { limit -> "summary-for-$limit" },
        )
        assertEquals("Checking history.\n\nsummary-for-3", result.responseText)
        assertFalse(result.accessGranted)
    }

    @Test
    fun parseBackendResult_plainText_noTools() {
        val result = NegotiationManagerLogic.parseBackendResult(
            text = "Tell me more.",
            functionCalls = emptyList(),
            negotiationType = NegotiationType.GATEKEEPER,
        )
        assertEquals("Tell me more.", result.responseText)
        assertFalse(result.accessGranted)
    }

    // ── Usage history summary ────────────────────────────────────────

    @Test
    fun buildUsageHistorySummary_blankPackage() {
        val text = NegotiationManagerLogic.buildUsageHistorySummary("", emptyList(), 5)
        assertTrue(text.contains("don't have a target app context"))
    }

    @Test
    fun buildUsageHistorySummary_formatsSessions() {
        val sessions = listOf(
            UsageSession(
                id = 1,
                packageName = "com.x",
                startTimestamp = 0L,
                endTimestamp = 60_000L,
                timerDurationMs = 120_000L,
                overrunMs = 0,
                closedOnTime = true,
                karmaChange = -1,
            ),
        )
        val text = NegotiationManagerLogic.buildUsageHistorySummary("com.x", sessions, 5)
        assertTrue(text.contains("Most recent 1 usage sessions"))
        assertTrue(text.contains("closed on time"))
        assertTrue(text.contains("karma -1"))
    }

    // ── Reply path: mocked generate fixture → mapBackendReply ────────

    @Test
    fun mapBackendReply_grantAccessBlockedUntilMinRounds() {
        // Fixture as if BackendClient.generate returned grantAccess too early
        val generateFixture = BackendClient.GenerateResponse(
            result = "You've convinced me.",
            function_calls = listOf(BackendClient.FunctionCall("grantAccess")),
        )
        val result = NegotiationManagerLogic.mapBackendReply(
            text = generateFixture.result ?: "",
            functionCalls = generateFixture.function_calls,
            negotiationType = NegotiationType.GATEKEEPER,
            exchangeCount = 1,
            minRounds = 3,
            maxRounds = 6,
        )
        assertFalse(result.accessGranted)
        assertTrue(result.responseText.contains("You've convinced me."))
        assertFalse(result.responseText.contains("One more quick reflection"))
        assertFalse(result.responseText.contains("round", ignoreCase = true))
        assertFalse(result.responseText.contains("turn", ignoreCase = true))
    }

    @Test
    fun mapBackendReply_grantAccessAllowedAfterMinRounds() {
        val generateFixture = BackendClient.GenerateResponse(
            result = "Go ahead.",
            function_calls = listOf(BackendClient.FunctionCall("grantAccess")),
        )
        val result = NegotiationManagerLogic.mapBackendReply(
            text = generateFixture.result ?: "",
            functionCalls = generateFixture.function_calls,
            negotiationType = NegotiationType.GATEKEEPER,
            exchangeCount = 3,
            minRounds = 3,
            maxRounds = 6,
        )
        assertTrue(result.accessGranted)
        assertEquals("Go ahead.", result.responseText)
    }

    @Test
    fun briefingHelpers_formatAndMerge() {
        assertEquals(
            "No apps are currently hidden.",
            NegotiationManagerLogic.buildHiddenAppsBriefing(emptyList()),
        )
        assertEquals(
            "Currently hidden apps:\n- Maps (com.maps), karma: -2, note: \"risky\"",
            NegotiationManagerLogic.buildHiddenAppsBriefing(
                listOf(
                    NegotiationManagerLogic.formatHiddenAppBriefingLine(
                        "Maps", "com.maps", -2, "risky",
                    ),
                ),
            ),
        )
        assertEquals(
            null,
            NegotiationManagerLogic.buildAppNotesBriefing(emptyList()),
        )
        assertEquals(
            "App notes:\n- X (com.x): \"n\" (needs extra confirmation: true)",
            NegotiationManagerLogic.buildAppNotesBriefing(
                listOf(
                    NegotiationManagerLogic.formatAppNoteBriefingLine("X", "com.x", "n", true),
                ),
            ),
        )
        assertEquals(
            "Installed apps available to launch:\n- Chrome (com.chrome)",
            NegotiationManagerLogic.buildInstalledAppsBriefing(
                listOf(NegotiationManagerLogic.formatInstalledAppBriefingLine("Chrome", "com.chrome")),
            ),
        )
        assertEquals(
            "base",
            NegotiationManagerLogic.mergeSystemPromptWithDailySummaries("base", null),
        )
        assertTrue(
            NegotiationManagerLogic.mergeSystemPromptWithDailySummaries("base", "### day\nsum")
                .contains("### day"),
        )
        assertEquals(
            "Recent daily log summaries (most recent first):\n### d1\nhello",
            NegotiationManagerLogic.formatDailySummariesBriefing(listOf("d1" to "hello")),
        )
    }

    @Test
    fun scriptedGateOpeningResult_neverGrants() {
        val result = NegotiationManagerLogic.scriptedGateOpeningResult("It's focus time.")
        assertEquals("It's focus time.", result.responseText)
        assertFalse(result.accessGranted)
        assertEquals(0, result.extensionMinutes)
        assertEquals("", result.launchedPackage)
    }

    @Test
    fun mergeSystemPromptWithOpening_includesOpeningAndDoNotRepeat() {
        val merged = NegotiationManagerLogic.mergeSystemPromptWithOpening(
            systemPrompt = "Be brief.",
            opening = "Hi there",
            userContext = "Session is 20 minutes.",
        )
        assertTrue(merged.contains("Be brief."))
        assertTrue(merged.contains("Session is 20 minutes."))
        assertTrue(merged.contains("Hi there"))
        assertTrue(merged.contains("do not repeat"))
        assertEquals(
            "Be brief.",
            NegotiationManagerLogic.mergeSystemPromptWithOpening("Be brief.", "Hi")
                .substringBefore("\n\n"),
        )
    }

    @Test
    fun replyRoutingHelpers() {
        assertTrue(NegotiationManagerLogic.shouldIncrementExchangeBeforeReply(NegotiationType.GATEKEEPER))
        assertTrue(NegotiationManagerLogic.shouldIncrementExchangeBeforeReply(NegotiationType.FOCUS_GATE))
        assertFalse(NegotiationManagerLogic.shouldIncrementExchangeBeforeReply(NegotiationType.NUDGE))
        assertFalse(NegotiationManagerLogic.shouldIncrementExchangeBeforeReply(null))
        assertTrue(NegotiationManagerLogic.isModelNotFoundCode("model_not_found"))
        assertFalse(NegotiationManagerLogic.isModelNotFoundCode("other"))
        assertTrue(
            NegotiationManagerLogic.modelNotFoundMessage("m1").contains("m1"),
        )
    }

    @Test
    fun formatParsedToolParamsLine_coversKnownTools() {
        val args = mapOf("minutes" to JsonPrimitive("7"), "packageName" to JsonPrimitive("com.app"), "query" to JsonPrimitive("mail"), "limit" to JsonPrimitive("3"))
        assertEquals(
            "tool params parsed: grantExtension(minutes=7)",
            NegotiationManagerLogic.formatParsedToolParamsLine("grantExtension", args),
        )
        assertTrue(
            NegotiationManagerLogic.formatParsedToolParamsLine("launchApp", args)!!.contains("com.app"),
        )
        assertTrue(
            NegotiationManagerLogic.formatParsedToolParamsLine("suggestApps", args)!!.contains("mail"),
        )
        assertTrue(
            NegotiationManagerLogic.formatParsedToolParamsLine("presentSuggestions", args)!!.contains("mail"),
        )
        assertEquals(
            "tool params parsed: queryRecentUsageSessions(limit=3)",
            NegotiationManagerLogic.formatParsedToolParamsLine("queryRecentUsageSessions", args),
        )
        assertEquals(null, NegotiationManagerLogic.formatParsedToolParamsLine("unknown", args))
    }

    @Test
    fun formatToolDeclarationNames_extractsNames() {
        val tools = listOf(
            mapOf(
                "functionDeclarations" to kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject { put("name", JsonPrimitive("grantAccess")) })
                },
            ),
        )
        assertEquals("[grantAccess]", NegotiationManagerLogic.formatToolDeclarationNames(tools))
    }
}
