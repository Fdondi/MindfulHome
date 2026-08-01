package com.mindfulhome.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSessionLogicTest {

    @Test
    fun parseLastTimerUsageSnapshotJson_happyPathAndRejects() {
        val json = """
            {
              "capturedAtMs": 1000,
              "topApps": [
                {
                  "packageName": "com.app",
                  "foregroundTimeMs": 500,
                  "longestSessionsMsDesc": [400, 0, 100]
                }
              ]
            }
        """.trimIndent()
        val snap = parseLastTimerUsageSnapshotJson(json)!!
        assertEquals(1000L, snap.capturedAtMs)
        assertEquals(1, snap.topApps.size)
        assertEquals(listOf(400L, 100L), snap.topApps[0].longestSessionsMsDesc)

        assertNull(parseLastTimerUsageSnapshotJson("""{"capturedAtMs":0,"topApps":[]}"""))
        assertNull(parseLastTimerUsageSnapshotJson("not-json"))
        assertNull(parseLastTimerUsageSnapshotJson("""{"capturedAtMs":1,"topApps":[]}"""))
        assertNull(
            parseLastTimerUsageSnapshotJson(
                """{"capturedAtMs":1,"topApps":[{"packageName":" ","foregroundTimeMs":10}]}""",
            ),
        )
    }

    @Test
    fun sessionHelpers() {
        assertTrue(isSameSavedSession("a", 10, 1, "a", 10, 1))
        assertFalse(isSameSavedSession("a", 10, 1, "b", 10, 1))
        assertEquals(5L, resolvePersistedSuspendedAtMs(5L, false, 9L))
        assertEquals(9L, resolvePersistedSuspendedAtMs(null, true, 9L))
        assertEquals(0L, resolvePersistedSuspendedAtMs(null, false, 9L))

        val session = buildSavedSession("pkg", 120_000L, 1_000L, 0L, 31_000L)!!
        assertEquals(90_000L, session.remainingMs)
        assertEquals(2, session.remainingMinutes)
        assertNull(buildSavedSession("", 10, 1, 0, 100))
        assertNull(buildSavedSession("pkg", 10, 1, 0, 100))
    }
}
