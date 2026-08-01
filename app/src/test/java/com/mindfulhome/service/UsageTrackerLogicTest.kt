package com.mindfulhome.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageTrackerLogicTest {

    @Test
    fun cachedPackageIfFresh_respectsBypassAndTtl() {
        assertNull(
            UsageTrackerLogic.cachedPackageIfFresh("a", 100, 150, 40, bypassCache = true),
        )
        assertEquals(
            "a",
            UsageTrackerLogic.cachedPackageIfFresh("a", 100, 130, 40, bypassCache = false),
        )
        assertNull(
            UsageTrackerLogic.cachedPackageIfFresh("a", 100, 150, 40, bypassCache = false),
        )
        assertNull(
            UsageTrackerLogic.cachedPackageIfFresh(null, 100, 110, 40, bypassCache = false),
        )
    }

    @Test
    fun eventHelpers() {
        assertTrue(UsageTrackerLogic.isForegroundTransitionEvent(1, 1, 2))
        assertTrue(UsageTrackerLogic.isForegroundTransitionEvent(2, 1, 2))
        assertFalse(UsageTrackerLogic.isForegroundTransitionEvent(3, 1, 2))
        assertTrue(
            UsageTrackerLogic.shouldReplaceLatestForeground("pkg", 10, 5, isForegroundEvent = true),
        )
        assertFalse(
            UsageTrackerLogic.shouldReplaceLatestForeground(null, 10, 5, isForegroundEvent = true),
        )
        assertFalse(
            UsageTrackerLogic.shouldReplaceLatestForeground("pkg", 4, 5, isForegroundEvent = true),
        )
        assertTrue(UsageTrackerLogic.isUserActivityEvent(7, 7, 1, includeForegroundTransitions = false))
        assertTrue(UsageTrackerLogic.isUserActivityEvent(1, 7, 1, includeForegroundTransitions = true))
        assertFalse(UsageTrackerLogic.isUserActivityEvent(1, 7, 1, includeForegroundTransitions = false))
        assertEquals(60_000L, UsageTrackerLogic.coerceLookbackMs(1_000L, 60_000L))
    }
}
