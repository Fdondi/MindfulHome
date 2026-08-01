package com.mindfulhome.ui.negotiation

import com.mindfulhome.ai.NegotiationResult
import com.mindfulhome.model.AppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NegotiationLogicTest {

    private fun app(pkg: String, label: String = pkg) = AppInfo(pkg, label, null)

    // --- extractLaunchQuery ---

    @Test
    fun extractLaunchQuery_blank_returnsEmpty() {
        assertEquals("", extractLaunchQuery(""))
        assertEquals("", extractLaunchQuery("   "))
    }

    @Test
    fun extractLaunchQuery_doubleQuoted_takesCapture() {
        assertEquals("Instagram", extractLaunchQuery("""Please open "Instagram" for me"""))
    }

    @Test
    fun extractLaunchQuery_singleQuoted_takesCapture() {
        assertEquals("Maps", extractLaunchQuery("app is 'Maps'"))
    }

    @Test
    fun extractLaunchQuery_quotedWinsOverMarkers() {
        assertEquals("X", extractLaunchQuery("""open Twitter but launch "X""""))
    }

    @Test
    fun extractLaunchQuery_firstMatchingMarkerInListWins() {
        // markers are tried in order; "open" precedes "launch", so this returns the open-suffix
        assertEquals("A launch B", extractLaunchQuery("open A launch B"))
    }

    @Test
    fun extractLaunchQuery_sameMarker_lastOccurrenceWins() {
        assertEquals("second", extractLaunchQuery("open first open second"))
    }

    @Test
    fun extractLaunchQuery_markersAreCaseInsensitive() {
        assertEquals("Chrome", extractLaunchQuery("Please OPEN Chrome"))
        assertEquals("Slack", extractLaunchQuery("App Name Is Slack"))
        assertEquals("Gmail", extractLaunchQuery("the app is Gmail"))
    }

    @Test
    fun extractLaunchQuery_noMarker_returnsTrimmed() {
        assertEquals("just some text", extractLaunchQuery("  just some text  "))
    }

    @Test
    fun extractLaunchQuery_markerWithBlankSuffix_fallsThroughToTrimmed() {
        // lastIndexOf("open") hits trailing "open" with empty candidate → return full trimmed
        assertEquals("please open", extractLaunchQuery("please open"))
    }

    // --- classifyNegotiationMode ---

    @Test
    fun classifyNegotiationMode_focusGate() {
        val mode = classifyNegotiationMode(
            packageName = "",
            extendGate = false,
            focusModeActive = true,
        )
        assertTrue(mode.isFocusGate)
        assertFalse(mode.isExtendGate)
        assertTrue(mode.isGateFlow)
    }

    @Test
    fun classifyNegotiationMode_extendGate() {
        val mode = classifyNegotiationMode(
            packageName = "com.example.app",
            extendGate = true,
            focusModeActive = false,
        )
        assertFalse(mode.isFocusGate)
        assertTrue(mode.isExtendGate)
        assertTrue(mode.isGateFlow)
    }

    @Test
    fun classifyNegotiationMode_gatekeeper() {
        val mode = classifyNegotiationMode(
            packageName = "com.example.app",
            extendGate = false,
            focusModeActive = true,
        )
        assertFalse(mode.isFocusGate)
        assertFalse(mode.isExtendGate)
        assertTrue(mode.isGateFlow)
    }

    @Test
    fun classifyNegotiationMode_generalChat() {
        val mode = classifyNegotiationMode(
            packageName = "",
            extendGate = false,
            focusModeActive = false,
        )
        assertFalse(mode.isFocusGate)
        assertFalse(mode.isExtendGate)
        assertFalse(mode.isGateFlow)
    }

    @Test
    fun classifyNegotiationMode_extendWithEmptyPackage_notExtendGate() {
        val mode = classifyNegotiationMode(
            packageName = "",
            extendGate = true,
            focusModeActive = true,
        )
        // extend requires non-empty package; empty+extend+focus is not focus gate either
        assertFalse(mode.isFocusGate)
        assertFalse(mode.isExtendGate)
        assertFalse(mode.isGateFlow)
    }

    // --- decideLaunchStrategy ---

    private val generalMode = classifyNegotiationMode("", extendGate = false, focusModeActive = false)
    private val focusMode = classifyNegotiationMode("", extendGate = false, focusModeActive = true)
    private val extendMode = classifyNegotiationMode("com.app", extendGate = true, focusModeActive = false)
    private val gatekeeperMode = classifyNegotiationMode("com.app", extendGate = false, focusModeActive = false)

    @Test
    fun decideLaunchStrategy_focusGate_returnsNone() {
        assertEquals(
            LaunchStrategy.None,
            decideLaunchStrategy(
                NegotiationResult("ok", launchedPackage = "com.x", showSuggestions = true),
                focusMode,
                packageName = "",
            ),
        )
    }

    @Test
    fun decideLaunchStrategy_extendGate_returnsNone() {
        assertEquals(
            LaunchStrategy.None,
            decideLaunchStrategy(
                NegotiationResult("ok", launchedPackage = "com.x"),
                extendMode,
                packageName = "com.app",
            ),
        )
    }

    @Test
    fun decideLaunchStrategy_directLaunch_winsOverSuggestions() {
        val strategy = decideLaunchStrategy(
            NegotiationResult(
                "go",
                launchedPackage = "com.maps",
                suggestedQuery = "maps",
                showSuggestions = true,
            ),
            generalMode,
            packageName = "",
            fallbackQuery = "fallback",
        )
        assertEquals(LaunchStrategy.DirectLaunch("com.maps"), strategy)
    }

    @Test
    fun decideLaunchStrategy_showChooser_usesSuggestedQuery() {
        val strategy = decideLaunchStrategy(
            NegotiationResult("pick", suggestedQuery = "instagram", showSuggestions = true),
            generalMode,
            packageName = "",
            fallbackQuery = "fallback",
        )
        assertEquals(LaunchStrategy.ShowChooser("instagram"), strategy)
    }

    @Test
    fun decideLaunchStrategy_showChooser_blankQueryUsesFallback() {
        val strategy = decideLaunchStrategy(
            NegotiationResult("pick", suggestedQuery = "", showSuggestions = true),
            generalMode,
            packageName = "",
            fallbackQuery = "last request",
        )
        assertEquals(LaunchStrategy.ShowChooser("last request"), strategy)
    }

    @Test
    fun decideLaunchStrategy_continueChat_whenNoTools() {
        assertEquals(
            LaunchStrategy.ContinueChat,
            decideLaunchStrategy(
                NegotiationResult("hmm"),
                generalMode,
                packageName = "",
            ),
        )
    }

    @Test
    fun decideLaunchStrategy_gatekeeper_withoutLaunch_returnsNone() {
        assertEquals(
            LaunchStrategy.None,
            decideLaunchStrategy(
                NegotiationResult("granted", accessGranted = true, showSuggestions = true),
                gatekeeperMode,
                packageName = "com.app",
            ),
        )
    }

    @Test
    fun decideLaunchStrategy_gatekeeper_withLaunch_returnsDirect() {
        assertEquals(
            LaunchStrategy.DirectLaunch("com.other"),
            decideLaunchStrategy(
                NegotiationResult("go", launchedPackage = "com.other"),
                gatekeeperMode,
                packageName = "com.app",
            ),
        )
    }

    // --- findExactMatchPackage ---

    private val apps = listOf(
        app("com.instagram.android", "Instagram"),
        app("com.google.android.apps.maps", "Maps"),
        app("com.twitter.android", "X"),
    )

    @Test
    fun findExactMatchPackage_blankQuery_returnsNull() {
        assertNull(findExactMatchPackage("", apps))
        assertNull(findExactMatchPackage("!!!", apps))
    }

    @Test
    fun findExactMatchPackage_byLabel() {
        assertEquals("com.instagram.android", findExactMatchPackage("Instagram", apps))
        assertEquals("com.instagram.android", findExactMatchPackage("in sta gram", apps))
    }

    @Test
    fun findExactMatchPackage_byFullPackage() {
        assertEquals(
            "com.google.android.apps.maps",
            findExactMatchPackage("com.google.android.apps.maps", apps),
        )
    }

    @Test
    fun findExactMatchPackage_byShortPackageSegment() {
        val spotify = listOf(app("com.spotify.music", "Spotify"))
        assertEquals("com.spotify.music", findExactMatchPackage("music", spotify))
        assertEquals("com.spotify.music", findExactMatchPackage("Music", spotify))
    }

    @Test
    fun findExactMatchPackage_noMatch_returnsNull() {
        assertNull(findExactMatchPackage("TikTok", apps))
    }

    @Test
    fun findExactMatchPackage_firstMatchWins() {
        val dupes = listOf(
            app("com.a", "Maps"),
            app("com.b", "Maps"),
        )
        assertEquals("com.a", findExactMatchPackage("Maps", dupes))
    }

    // --- applyGateOutcome (smoke; preserves grant semantics) ---

    @Test
    fun applyGateOutcome_extendWithMinutes_grants() {
        val outcome = applyGateOutcome(
            extendMode,
            NegotiationResult("ok", extensionMinutes = 5),
        )
        assertEquals(GateOutcome.Granted(5), outcome)
    }

    @Test
    fun applyGateOutcome_extendWithoutMinutes_noChange() {
        assertEquals(
            GateOutcome.NoChange,
            applyGateOutcome(extendMode, NegotiationResult("not yet")),
        )
    }

    @Test
    fun applyGateOutcome_accessGranted_grants() {
        assertEquals(
            GateOutcome.Granted(),
            applyGateOutcome(focusMode, NegotiationResult("yes", accessGranted = true)),
        )
    }

    @Test
    fun applyGateOutcome_denied_noChange() {
        assertEquals(
            GateOutcome.NoChange,
            applyGateOutcome(gatekeeperMode, NegotiationResult("no")),
        )
    }
}
