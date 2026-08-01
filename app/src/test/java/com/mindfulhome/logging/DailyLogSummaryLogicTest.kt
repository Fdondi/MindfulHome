package com.mindfulhome.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DailyLogSummaryLogicTest {

    @Test
    fun gateAndRegenerateFlags() {
        assertEquals(
            DailySummaryDayGate.Ok,
            gateDailySummaryDay(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2)),
        )
        assertEquals(
            DailySummaryDayGate.DayNotConcluded,
            gateDailySummaryDay(LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 2)),
        )
        assertTrue(shouldRunDailySummaryRegenerate(1, 2))
        assertFalse(shouldRunDailySummaryRegenerate(0, 2))
        assertFalse(shouldRunDailySummaryRegenerate(1, 0))
    }

    @Test
    fun buildDailySummaryPrompt_includesKeyFields() {
        val prompt = buildDailySummaryPrompt("Do it", "2026-01-01", 2, 9, "- event")
        assertTrue(prompt.contains("Do it"))
        assertTrue(prompt.contains("Day: 2026-01-01"))
        assertTrue(prompt.contains("Sessions: 2"))
        assertTrue(prompt.contains("Events: 9"))
        assertTrue(prompt.contains("- event"))
        assertTrue(prompt.contains("tagline"))
    }
}
