package com.mindfulhome.ai

import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.mindfulhome.MainActivity
import com.mindfulhome.ai.backend.ApiKeyManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * Reproduces pre-Android 14 Credential Manager reporting user-cancel when this
 * app is the default HOME. Run on a Pixel 4 API 29 emulator with Play Services.
 *
 * Desired: the Google account picker stays up, or sign-in succeeds, with no tap.
 * Android 10 bug: Play Services `assistedsignin.ui.GoogleSignInActivity` is empty
 * and/or [GetCredentialCancellationException] fires without a tap.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29, maxSdkVersion = 29)
class GoogleSignInAndroid10InstrumentedTest {

    @Before
    fun clearLogcat() {
        execShell("logcat -c")
    }

    @After
    fun dismissSignInUi() {
        repeat(4) {
            execShell("input keyevent 4")
            Thread.sleep(250L)
        }
    }

    @Test
    fun productionSignIn_doesNotCancelWithoutUserAction() {
        assertTrue("This reproduction is for Android 10 (API 29)", Build.VERSION.SDK_INT == 29)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                GoogleSignInActivity.start(activity, forceAccountPicker = true)
            }
            Thread.sleep(PROBE_MS)
            failIfFalseCancel(snapshot(), "after GIS launch")
        } finally {
            scenario.close()
        }
    }
}

private const val PROBE_MS = 4_000L

private data class SignInSnapshot(
    val gotCredential: Boolean,
    val cancellationLogged: Boolean,
    val activityDump: String,
    val windowXml: String,
)

private fun snapshot(): SignInSnapshot {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val gotCredential = runCatching { runBlocking { ApiKeyManager.isSignedIn(context) } }.getOrDefault(false)
    return SignInSnapshot(
        gotCredential = gotCredential,
        cancellationLogged = PreUSignInProbe.logContainsCredentialCancellation(
            execShell("logcat -d"),
        ),
        activityDump = execShell("dumpsys activity activities"),
        windowXml = windowHierarchy(),
    )
}

private fun failIfFalseCancel(snap: SignInSnapshot, phase: String) {
    val pickerShowing = PreUSignInProbe.dumpsysShowsGoogleIdPicker(snap.activityDump)
    val verdict = PreUSignInProbe.verdict(
        cancellationLogged = snap.cancellationLogged,
        signInUiShowing = pickerShowing,
        emptyHostOnly = PreUSignInProbe.dumpsysShowsEmptySignInHost(snap.activityDump),
        gotCredential = snap.gotCredential,
        accountChooserVisible = PreUSignInProbe.windowDumpShowsAccountChooser(snap.windowXml),
    )
    if (verdict == PreUSignInProbe.Verdict.UiStillShowing ||
        verdict == PreUSignInProbe.Verdict.SignedIn
    ) {
        return
    }
    fail(
        "Android 10 Google Sign-In false cancel reproduced $phase: verdict=$verdict " +
            "cancellationLogged=${snap.cancellationLogged} pickerShowing=$pickerShowing. " +
            "Resumed: ${resumedLine(snap.activityDump)} texts=${windowTexts(snap.windowXml)}",
    )
}

private fun resumedLine(dump: String): String =
    dump.lineSequence().firstOrNull { it.contains("mResumedActivity") }?.trim()
        ?: dump.lineSequence().firstOrNull { it.contains("topResumedActivity") }?.trim()
        ?: "(no resumed activity line)"

private fun windowHierarchy(): String {
    execShell("uiautomator dump /data/local/tmp/signin_uidump.xml")
    return execShell("cat /data/local/tmp/signin_uidump.xml")
}

private fun windowTexts(xml: String): String =
    Regex("""text="([^"]+)"""")
        .findAll(xml)
        .map { it.groupValues[1] }
        .filter { it.isNotBlank() }
        .take(12)
        .joinToString("|")
        .ifBlank { "(no text nodes)" }

private fun execShell(command: String): String {
    val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    FileInputStream(pfd.fileDescriptor).use { input ->
        return input.bufferedReader().use { it.readText() }
    }
}
