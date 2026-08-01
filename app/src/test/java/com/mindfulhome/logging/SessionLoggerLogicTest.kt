package com.mindfulhome.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLoggerLogicTest {

    @Test
    fun shouldDebounceSessionStart_windowAndEventCount() {
        assertTrue(shouldDebounceSessionStart(true, 100L, 1000L, 1))
        assertFalse(shouldDebounceSessionStart(true, 2000L, 1000L, 1))
        assertFalse(shouldDebounceSessionStart(true, 100L, 1000L, 2))
        assertFalse(shouldDebounceSessionStart(false, 100L, 1000L, 1))
    }

    @Test
    fun decideSessionLogWrite_paths() {
        assertEquals(
            SessionLogWriteDecision.Stale,
            decideSessionLogWrite(1, 2, 10, false),
        )
        assertEquals(
            SessionLogWriteDecision.WriteNow(10),
            decideSessionLogWrite(1, 1, 10, false),
        )
        assertEquals(
            SessionLogWriteDecision.QueuePending,
            decideSessionLogWrite(1, 1, 0, true),
        )
        assertEquals(
            SessionLogWriteDecision.QueueAndRecover,
            decideSessionLogWrite(1, 1, 0, false),
        )
    }
}
