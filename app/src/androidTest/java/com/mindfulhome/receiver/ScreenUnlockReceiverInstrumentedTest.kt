package com.mindfulhome.receiver

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mindfulhome.MainActivity
import com.mindfulhome.service.TimerService
import com.mindfulhome.settings.SettingsManager
import org.junit.After
import org.junit.Assume.assumeNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

@RunWith(AndroidJUnit4::class)
class ScreenUnlockReceiverInstrumentedTest {

    private lateinit var appContext: Context
    private lateinit var receiver: ScreenUnlockReceiver

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        receiver = ScreenUnlockReceiver()
        MainActivity.shouldShowTimer = false
        SettingsManager.clearQuickLaunchSession(appContext)
    }

    @After
    fun tearDown() {
        SettingsManager.clearQuickLaunchSession(appContext)
        MainActivity.shouldShowTimer = false
    }

    @Test
    fun unlock_duringQuickLaunchSession_doesNotRelaunchMainActivity() {
        SettingsManager.startQuickLaunchSession(
            context = appContext,
            allowedPackages = setOf("com.example.quicklaunch"),
        )
        val capturingContext = CapturingContext(appContext)

        receiver.onReceive(capturingContext, Intent(Intent.ACTION_USER_PRESENT))

        assertFalse(MainActivity.shouldShowTimer)
        assertNull(capturingContext.startedIntent)
    }

    @Test
    fun unlock_withoutQuickLaunchSession_launchesMainActivityWithUnlockExtra() {
        val capturingContext = CapturingContext(appContext)

        receiver.onReceive(capturingContext, Intent(Intent.ACTION_USER_PRESENT))

        val started = capturingContext.startedIntent
        assertNotNull(started)
        assertTrue(MainActivity.shouldShowTimer)
        assertNotNull(started?.component)
        assertEquals(MainActivity::class.java.name, started?.component?.className)
        assertTrue(started?.getBooleanExtra(ScreenUnlockReceiver.EXTRA_FROM_UNLOCK, false) == true)
    }

    @Test
    fun unlock_keepsQuickLaunchAppInForeground() {
        val packageManager = appContext.packageManager
        val quickLaunchPackage = listOf("com.android.settings", "com.google.android.apps.settings")
            .firstOrNull { pkg -> packageManager.getLaunchIntentForPackage(pkg) != null }
        assumeNotNull("No launchable Settings app package available for this test device", quickLaunchPackage)
        val targetPackage = quickLaunchPackage ?: return
        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
        assumeNotNull("No launch intent for $targetPackage", launchIntent)

        TimerService.startQuickLaunchSession(
            context = appContext,
            initialPackageName = targetPackage,
            allowedQuickLaunchPackages = listOf(targetPackage),
        )
        assertQuickLaunchSessionEventually(expected = true)

        appContext.startActivity(
            launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        assertForegroundEventually(targetPackage)

        execShell("input keyevent 26")
        Thread.sleep(800L)
        execShell("input keyevent 26")
        Thread.sleep(1000L)
        execShell("wm dismiss-keyguard")
        Thread.sleep(1000L)
        execShell("am broadcast -a android.intent.action.USER_PRESENT")
        Thread.sleep(1200L)

        assertQuickLaunchSessionEventually(expected = true)
        assertForegroundEventually(targetPackage)
        assertNotEquals("com.mindfulhome", currentForegroundPackage())
    }

    private class CapturingContext(base: Context) : ContextWrapper(base) {
        var startedIntent: Intent? = null

        override fun startActivity(intent: Intent?) {
            startedIntent = intent
        }
    }

    private fun assertForegroundEventually(expectedPackage: String) {
        repeat(12) { attempt ->
            val current = currentForegroundPackage()
            if (current == expectedPackage) return
            if (attempt < 11) Thread.sleep(500L)
        }
        assertEquals(expectedPackage, currentForegroundPackage())
    }

    private fun assertQuickLaunchSessionEventually(expected: Boolean) {
        repeat(12) { attempt ->
            val active = SettingsManager.isQuickLaunchSessionActive(appContext)
            if (active == expected) return
            if (attempt < 11) Thread.sleep(500L)
        }
        assertEquals(expected, SettingsManager.isQuickLaunchSessionActive(appContext))
    }

    private fun currentForegroundPackage(): String? {
        val activityDump = execShell("dumpsys activity activities")
        val resumedPatterns = listOf(
            Regex("""mResumedActivity:.*\s([A-Za-z0-9._]+)/"""),
            Regex("""topResumedActivity=ActivityRecord\{[^}]*\s([A-Za-z0-9._]+)/"""),
            Regex("""ResumedActivity:.*\s([A-Za-z0-9._]+)/"""),
        )
        for (pattern in resumedPatterns) {
            val match = pattern.find(activityDump)
            val pkg = match?.groupValues?.getOrNull(1)
            if (!pkg.isNullOrBlank()) return pkg
        }

        val windowDump = execShell("dumpsys window windows")
        val focusMatch = Regex("""mCurrentFocus=Window\{[^}]*\s([A-Za-z0-9._]+)/""")
            .find(windowDump)
        return focusMatch?.groupValues?.getOrNull(1)
    }

    private fun execShell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        FileInputStream(pfd.fileDescriptor).use { input ->
            return input.bufferedReader().use { it.readText() }
        }
    }
}
