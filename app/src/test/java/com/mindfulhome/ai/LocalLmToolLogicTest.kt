package com.mindfulhome.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLmToolLogicTest {

    @Test
    fun intArg_readsIntegerAndFallsBack() {
        assertEquals(7, LocalLmToolLogic.intArg("""{"limit":7}""", "limit", 5))
        assertEquals(5, LocalLmToolLogic.intArg("""{"limit":"nope"}""", "limit", 5))
        assertEquals(5, LocalLmToolLogic.intArg("not-json", "limit", 5))
        assertEquals(12, LocalLmToolLogic.intArg("""{"limit":"12"}""", "limit", 5))
    }

    @Test
    fun stringArg_readsAndDefaults() {
        assertEquals("com.foo", LocalLmToolLogic.stringArg("""{"packageName":"com.foo"}""", "packageName"))
        assertEquals("", LocalLmToolLogic.stringArg("{}", "packageName"))
        assertEquals("fallback", LocalLmToolLogic.stringArg("nope", "packageName", "fallback"))
    }

    @Test
    fun gatekeeperGrantAccess_setsFlag() = runBlocking {
        val tools = GatekeeperTools()
        val result = tools.invoke("grantAccess", "{}")
        assertTrue(tools.accessGranted)
        assertTrue(result.contains("launched"))
        tools.reset()
        assertFalse(tools.accessGranted)
    }

    @Test
    fun nudgeGrantExtension_parsesMinutes() = runBlocking {
        val tools = NudgeTools()
        tools.invoke("grantExtension", """{"minutes":8}""")
        assertEquals(8, tools.extensionMinutes)
    }

    @Test
    fun generalChatLaunchApp_recordsPackage() = runBlocking {
        val tools = GeneralChatTools()
        tools.invoke("launchApp", """{"packageName":"com.instagram.android"}""")
        assertEquals("com.instagram.android", tools.launchedPackage)
        tools.invoke("presentSuggestions", """{"query":"maps"}""")
        assertTrue(tools.showSuggestions)
        assertEquals("maps", tools.suggestedQuery)
    }

    @Test
    fun gatekeeperUsageHistory_usesResolver() = runBlocking {
        val tools = GatekeeperTools()
        tools.setUsageHistoryResolver { limit -> "sessions=$limit" }
        val result = tools.invoke("queryRecentUsageSessions", """{"limit":3}""")
        assertEquals("sessions=3", tools.lastUsageHistorySummary)
        assertTrue(result.contains("ok"))
    }

    @Test
    fun unknownTool_returnsStatus() = runBlocking {
        val result = FocusGateTools().invoke("nope", "{}")
        assertTrue(result.contains("unknown_tool"))
    }
}
