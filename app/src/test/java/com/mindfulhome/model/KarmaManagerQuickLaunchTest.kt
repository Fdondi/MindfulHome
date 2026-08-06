package com.mindfulhome.model

import org.junit.Assert.assertEquals
import org.junit.Test

class KarmaManagerQuickLaunchTest {

    @Test
    fun quickLaunchAllowedStayMs_zeroOrNegativeOne_getsFullBaseGrace() {
        assertEquals(60_000L, KarmaManager.quickLaunchAllowedStayMs(0, 60_000L))
        assertEquals(60_000L, KarmaManager.quickLaunchAllowedStayMs(-1, 60_000L))
    }

    @Test
    fun quickLaunchAllowedStayMs_negativeKarma_dividesBaseGraceByAbsKarma() {
        assertEquals(30_000L, KarmaManager.quickLaunchAllowedStayMs(-2, 60_000L))
        assertEquals(20_000L, KarmaManager.quickLaunchAllowedStayMs(-3, 60_000L))
        assertEquals(6_000L, KarmaManager.quickLaunchAllowedStayMs(-10, 60_000L))
    }

    @Test
    fun quickLaunchAllowedStayMs_positiveKarma_returnsBaseGraceUnchanged() {
        assertEquals(90_000L, KarmaManager.quickLaunchAllowedStayMs(3, 90_000L))
    }
}
