package com.mindfulhome.ui.timer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.runBlocking

class TimerLogicTest {

    @Test
    fun formatTimerMinutes_variants() {
        assertEquals("45 min", formatTimerMinutes(45))
        assertEquals("2 hr", formatTimerMinutes(120))
        assertEquals("1 hr 30 min", formatTimerMinutes(90))
    }

    @Test
    fun formatTimerEndTime_usesFormatter() {
        val fmt = SimpleDateFormat("HH:mm", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        assertEquals("00:05", formatTimerEndTime(0L, 5, fmt))
    }

    @Test
    fun formatUsageDurationAndBreakdown() {
        assertEquals("5m", formatUsageDuration(5 * 60_000L))
        assertEquals("1h", formatUsageDuration(60 * 60_000L))
        assertEquals("1h 5m", formatUsageDuration(65 * 60_000L))
        assertEquals("5m", formatUsageBreakdown(5 * 60_000L, listOf(5 * 60_000L)))
        assertEquals(
            "10m (6m + 4m)",
            formatUsageBreakdown(10 * 60_000L, listOf(6 * 60_000L, 4 * 60_000L)),
        )
        assertEquals(
            "10m (5m + 3m + 1m + ...)",
            formatUsageBreakdown(
                10 * 60_000L,
                listOf(5 * 60_000L, 3 * 60_000L, 1 * 60_000L, 1 * 60_000L),
            ),
        )
    }

    @Test
    fun oddVisiblePickerCount_andHelpers() {
        assertEquals(1, oddVisiblePickerCount(1))
        assertEquals(3, oddVisiblePickerCount(4))
        assertEquals(5, oddVisiblePickerCount(5))
        assertEquals(15, hardDeadlineMinutesOrNull(true, 15))
        assertNull(hardDeadlineMinutesOrNull(false, 15))
        assertTrue(msUntilNextMinuteBoundary(0L) >= 1_000L)
        assertTrue(msUntilNextMinuteBoundary(59_000L) >= 1_000L)
        assertEquals(-50, pickerCenterOffsetPx(200, 100))
        assertEquals(5, coercePrefillMinutes(5))
        assertNull(coercePrefillMinutes(null))
        assertEquals(1f, distanceAlpha(0), 0f)
        assertEquals(0.6f, distanceAlpha(1), 0f)
        assertEquals(0.3f, distanceAlpha(2), 0f)
    }

    @Test
    fun applyTimerPrefill_skipsNonPositiveToken() = runBlocking {
        var reason: String? = null
        var scrolled: Int? = null
        var applied = false
        applyTimerPrefill(
            prefillToken = 0L,
            initialReason = "x",
            initialMinutes = 10,
            setReason = { reason = it },
            scrollToMinutes = { scrolled = it },
            onPrefillApplied = { applied = true },
        )
        assertNull(reason)
        assertNull(scrolled)
        assertFalseApplied(applied)

        applyTimerPrefill(
            prefillToken = 1L,
            initialReason = "why",
            initialMinutes = 7,
            setReason = { reason = it },
            scrollToMinutes = { scrolled = it },
            onPrefillApplied = { applied = true },
        )
        assertEquals("why", reason)
        assertEquals(7, scrolled)
        assertTrueApplied(applied)
    }

    private fun assertFalseApplied(v: Boolean) = assertEquals(false, v)
    private fun assertTrueApplied(v: Boolean) = assertEquals(true, v)
}
