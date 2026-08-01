package com.mindfulhome.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundA11yLogicTest {

    @Test
    fun shouldIgnoreWindowStateEvent_filtersNoise() {
        assertTrue(
            ForegroundA11yLogic.shouldIgnoreWindowStateEvent(
                eventType = 1,
                windowStateChangedType = 2,
                packageName = "com.x",
                ownPackageName = "com.me",
                className = null,
                isImePackage = false,
                lastPackage = "",
            ),
        )
        assertTrue(
            ForegroundA11yLogic.shouldIgnoreWindowStateEvent(
                eventType = 2,
                windowStateChangedType = 2,
                packageName = "com.me",
                ownPackageName = "com.me",
                className = null,
                isImePackage = false,
                lastPackage = "",
            ),
        )
        assertTrue(
            ForegroundA11yLogic.shouldIgnoreWindowStateEvent(
                eventType = 2,
                windowStateChangedType = 2,
                packageName = "com.x",
                ownPackageName = "com.me",
                className = "android.widget.Toast",
                isImePackage = false,
                lastPackage = "",
            ),
        )
        assertTrue(
            ForegroundA11yLogic.shouldIgnoreWindowStateEvent(
                eventType = 2,
                windowStateChangedType = 2,
                packageName = "com.ime",
                ownPackageName = "com.me",
                className = null,
                isImePackage = true,
                lastPackage = "",
            ),
        )
        assertTrue(
            ForegroundA11yLogic.shouldIgnoreWindowStateEvent(
                eventType = 2,
                windowStateChangedType = 2,
                packageName = "com.x",
                ownPackageName = "com.me",
                className = null,
                isImePackage = false,
                lastPackage = "com.x",
            ),
        )
        assertFalse(
            ForegroundA11yLogic.shouldIgnoreWindowStateEvent(
                eventType = 2,
                windowStateChangedType = 2,
                packageName = "com.x",
                ownPackageName = "com.me",
                className = "com.x.Main",
                isImePackage = false,
                lastPackage = "com.y",
            ),
        )
    }

    @Test
    fun shouldNotifyTimerService() {
        assertTrue(ForegroundA11yLogic.shouldNotifyTimerService(true, false))
        assertTrue(ForegroundA11yLogic.shouldNotifyTimerService(false, true))
        assertFalse(ForegroundA11yLogic.shouldNotifyTimerService(false, false))
    }
}
