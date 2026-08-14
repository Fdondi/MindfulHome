package com.mindfulhome.ai

import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.mindfulhome.MainActivity
import com.mindfulhome.ai.backend.AuthManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
            val deferred = startProductionSignIn(scenario)
            Thread.sleep(PROBE_MS)
            failIfFalseCancel(snapshot(deferred), "after GIS launch")
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

private fun startProductionSignIn(
    scenario: ActivityScenario<MainActivity>,
): CompletableDeferred<AuthManager.SignInResult?> {
    val deferred = CompletableDeferred<AuthManager.SignInResult?>()
    val started = CountDownLatch(1)
    scenario.onActivity { activity ->
        Thread {
            runBlocking {
                started.countDown()
                try {
                    deferred.complete(
                        AuthManager.signIn(activity, forceAccountPicker = true),
                    )
                } catch (error: Throwable) {
                    deferred.completeExceptionally(error)
                }
            }
        }.start()
    }
    assertTrue("sign-in thread did not start", started.await(5, TimeUnit.SECONDS))
    return deferred
}

private fun snapshot(deferred: CompletableDeferred<AuthManager.SignInResult?>): SignInSnapshot {
    val gotCredential = deferred.isCompleted &&
        runCatching { runBlocking { deferred.await() } }.getOrNull() != null
    return SignInSnapshot(
        gotCredential = gotCredential,
        cancellationLogged = AuthManagerLogic.logContainsCredentialCancellation(
            execShell("logcat -d"),
        ),
        activityDump = execShell("dumpsys activity activities"),
        windowXml = windowHierarchy(),
    )
}

private fun failIfFalseCancel(snap: SignInSnapshot, phase: String) {
    val pickerShowing = AuthManagerLogic.dumpsysShowsGoogleIdPicker(snap.activityDump)
    val verdict = AuthManagerLogic.preUSignInProbeVerdict(
        cancellationLogged = snap.cancellationLogged,
        signInUiShowing = pickerShowing,
        emptyHostOnly = AuthManagerLogic.dumpsysShowsEmptySignInHost(snap.activityDump),
        gotCredential = snap.gotCredential,
        accountChooserVisible = AuthManagerLogic.windowDumpShowsAccountChooser(snap.windowXml),
    )
    if (verdict == AuthManagerLogic.PreUSignInProbeVerdict.UiStillShowing ||
        verdict == AuthManagerLogic.PreUSignInProbeVerdict.SignedIn
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
