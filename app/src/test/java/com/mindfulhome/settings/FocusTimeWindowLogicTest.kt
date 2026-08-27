package com.mindfulhome.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusTimeWindowLogicTest {

    @Test
    fun remaining_sameDayWindow() {
        val start = 9 * 60
        val end = 17 * 60
        assertEquals(
            8 * 60,
            FocusTimeWindowLogic.remainingMinutesUntilIntervalEnd(9 * 60, start, end),
        )
        assertEquals(
            1,
            FocusTimeWindowLogic.remainingMinutesUntilIntervalEnd(17 * 60 - 1, start, end),
        )
        assertTrue(FocusTimeWindowLogic.isMinuteWithinInterval(9 * 60, start, end))
        assertTrue(!FocusTimeWindowLogic.isMinuteWithinInterval(17 * 60, start, end))
    }

    @Test
    fun remaining_overnightWindow() {
        val start = 22 * 60
        val end = 6 * 60
        assertEquals(
            7 * 60,
            FocusTimeWindowLogic.remainingMinutesUntilIntervalEnd(23 * 60, start, end),
        )
        assertEquals(
            4 * 60,
            FocusTimeWindowLogic.remainingMinutesUntilIntervalEnd(2 * 60, start, end),
        )
        assertEquals(
            1,
            FocusTimeWindowLogic.remainingMinutesUntilIntervalEnd(6 * 60 - 1, start, end),
        )
        assertTrue(FocusTimeWindowLogic.isMinuteWithinInterval(23 * 60, start, end))
        assertTrue(FocusTimeWindowLogic.isMinuteWithinInterval(2 * 60, start, end))
        assertTrue(!FocusTimeWindowLogic.isMinuteWithinInterval(6 * 60, start, end))
    }

    @Test
    fun remaining_allDayWindow_untilSameClockTomorrow() {
        val clock = 9 * 60
        assertEquals(
            FocusTimeWindowLogic.MINUTES_PER_DAY,
            FocusTimeWindowLogic.remainingMinutesUntilIntervalEnd(clock, clock, clock),
        )
        assertEquals(
            FocusTimeWindowLogic.MINUTES_PER_DAY - 1,
            FocusTimeWindowLogic.remainingMinutesUntilIntervalEnd(clock + 1, clock, clock),
        )
        assertTrue(FocusTimeWindowLogic.isMinuteWithinInterval(0, clock, clock))
    }

    @Test
    fun remainingMinutesInActiveWindow_picksMatchingInterval() {
        val intervals = listOf(
            SettingsManager.FocusInterval(9 * 60, 12 * 60),
            SettingsManager.FocusInterval(14 * 60, 17 * 60),
        )
        assertEquals(
            60,
            FocusTimeWindowLogic.remainingMinutesInActiveWindow(11 * 60, intervals),
        )
        assertEquals(
            2 * 60,
            FocusTimeWindowLogic.remainingMinutesInActiveWindow(15 * 60, intervals),
        )
        assertNull(
            FocusTimeWindowLogic.remainingMinutesInActiveWindow(13 * 60, intervals),
        )
    }

    @Test
    fun durationParts_splitsHoursAndMinutes() {
        assertEquals(FocusTimeWindowLogic.DurationParts(0, 0), FocusTimeWindowLogic.durationParts(0))
        assertEquals(FocusTimeWindowLogic.DurationParts(0, 45), FocusTimeWindowLogic.durationParts(45))
        assertEquals(FocusTimeWindowLogic.DurationParts(2, 10), FocusTimeWindowLogic.durationParts(130))
        assertEquals(FocusTimeWindowLogic.DurationParts(1, 0), FocusTimeWindowLogic.durationParts(60))
    }
}
