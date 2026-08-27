package com.mindfulhome.ui.coachmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachmarkLogicTest {

    @Test
    fun shouldStartTour_replayOverridesDone() {
        assertTrue(shouldStartTour(done = true, alreadyShowing = false, pendingReplay = true))
        assertFalse(shouldStartTour(done = true, alreadyShowing = true, pendingReplay = true))
        assertFalse(shouldStartTour(done = true, alreadyShowing = false, pendingReplay = false))
    }

    @Test
    fun shouldAutoStartTour_onlyWhenUnseenAndIdle() {
        assertTrue(shouldAutoStartTour(done = false, alreadyShowing = false))
        assertFalse(shouldAutoStartTour(done = true, alreadyShowing = false))
        assertFalse(shouldAutoStartTour(done = false, alreadyShowing = true))
        assertFalse(shouldAutoStartTour(done = true, alreadyShowing = true))
    }

    @Test
    fun defaultPageStepIds_skipsStartWhenEmpty() {
        assertEquals(
            listOf(
                CoachmarkIds.TODO_CARD,
                CoachmarkIds.TODO_ADD,
                CoachmarkIds.QL_FOLDERS,
                CoachmarkIds.QL_SOMETHING_ELSE,
            ),
            defaultPageStepIds(hasOpenTodos = false),
        )
        assertEquals(
            listOf(
                CoachmarkIds.TODO_CARD,
                CoachmarkIds.TODO_ADD,
                CoachmarkIds.TODO_START,
                CoachmarkIds.QL_FOLDERS,
                CoachmarkIds.QL_SOMETHING_ELSE,
            ),
            defaultPageStepIds(hasOpenTodos = true),
        )
    }

    @Test
    fun overlayToursIncludeNotificationSteps() {
        assertEquals(CoachmarkIds.HOME_NOTIFICATIONS, homeCoachmarkSpecs()[1].id)
        assertEquals(CoachmarkIds.SETTINGS_NOTIFICATIONS, settingsCoachmarkSpecs()[2].id)
    }

    @Test
    fun scrollOffsetToReveal_movesTargetTowardDesiredTop() {
        assertEquals(0, scrollOffsetToReveal(currentScroll = 0, targetTopInRoot = 80f, desiredTop = 160f))
        assertEquals(340, scrollOffsetToReveal(currentScroll = 100, targetTopInRoot = 400f, desiredTop = 160f))
    }
}
