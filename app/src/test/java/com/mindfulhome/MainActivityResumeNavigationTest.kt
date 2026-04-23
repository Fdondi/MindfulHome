package com.mindfulhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainActivityResumeNavigationTest {

    @Test
    fun postBackgroundDestination_quickLaunchActive_returnsNoNavigation() {
        val destination = MainActivity.postBackgroundDestination(
            quickLaunchSessionActive = true,
            awayMs = 60_000L,
            timerWasRunning = false,
            quickReturnMs = 30_000L,
        )

        assertNull(destination)
    }

    @Test
    fun postBackgroundDestination_quickReturnWithRunningTimer_returnsHome() {
        val destination = MainActivity.postBackgroundDestination(
            quickLaunchSessionActive = false,
            awayMs = 10_000L,
            timerWasRunning = true,
            quickReturnMs = 60_000L,
        )

        assertEquals("home", destination)
    }

    @Test
    fun postBackgroundDestination_nonQuickLaunchResume_returnsDefault() {
        val destination = MainActivity.postBackgroundDestination(
            quickLaunchSessionActive = false,
            awayMs = 120_000L,
            timerWasRunning = true,
            quickReturnMs = 60_000L,
        )

        assertEquals("default", destination)
    }
}
