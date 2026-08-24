package com.mindfulhome.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class PreUSignInProbeTest {

    @Test
    fun verdict_falseCancelWhenFrameworkReportsUserCancel() {
        assertEquals(
            PreUSignInProbe.Verdict.FalseUserCancel,
            probe(cancellationLogged = true),
        )
    }

    @Test
    fun verdict_uiStillShowingWhenChooserVisible() {
        assertEquals(
            PreUSignInProbe.Verdict.UiStillShowing,
            probe(signInUiShowing = true, accountChooserVisible = true),
        )
    }

    @Test
    fun verdict_emptyGisWhenPickerHasNoChooser() {
        assertEquals(
            PreUSignInProbe.Verdict.EmptyGisUi,
            probe(signInUiShowing = true, accountChooserVisible = false),
        )
    }

    @Test
    fun verdict_signedInWinsOverCancelLog() {
        assertEquals(
            PreUSignInProbe.Verdict.SignedIn,
            probe(cancellationLogged = true, gotCredential = true),
        )
    }

    @Test
    fun verdict_endedWithoutUiWhenHostDies() {
        assertEquals(PreUSignInProbe.Verdict.EndedWithoutUi, probe())
    }

    @Test
    fun verdict_emptyHostIsNotTheGooglePicker() {
        assertEquals(
            PreUSignInProbe.Verdict.EmptyHostOnly,
            probe(emptyHostOnly = true),
        )
    }

    @Test
    fun dumpsysShowsEmptySignInHost_ignoresBareGoogleSignInActivity() {
        val dump = "mResumedActivity: ActivityRecord{abc u0 com.mindfulhome/.ai.GoogleSignInActivity t12}"
        assertEquals(true, PreUSignInProbe.dumpsysShowsEmptySignInHost(dump))
        assertEquals(false, PreUSignInProbe.dumpsysShowsGoogleIdPicker(dump))
    }

    @Test
    fun dumpsysShowsGoogleIdPicker_matchesSignInHub() {
        val dump = "mResumedActivity: ActivityRecord{abc u0 com.google.android.gms/.auth.api.signin.ui.SignInHubActivity t9}"
        assertEquals(true, PreUSignInProbe.dumpsysShowsGoogleIdPicker(dump))
        assertEquals(false, PreUSignInProbe.dumpsysShowsEmptySignInHost(dump))
    }

    @Test
    fun dumpsysShowsGoogleIdPicker_matchesGmsAssistedSignIn() {
        val dump = "mResumedActivity: ActivityRecord{56a679d u0 com.google.android.gms/.auth.api.credentials.assistedsignin.ui.GoogleSignInActivity t19}"
        assertEquals(true, PreUSignInProbe.dumpsysShowsGoogleIdPicker(dump))
        assertEquals(false, PreUSignInProbe.dumpsysShowsEmptySignInHost(dump))
    }

    @Test
    fun windowDumpShowsAccountChooser_detectsChooseAnAccount() {
        assertEquals(
            true,
            PreUSignInProbe.windowDumpShowsAccountChooser(
                """<node text="Choose an account" />""",
            ),
        )
        assertEquals(
            false,
            PreUSignInProbe.windowDumpShowsAccountChooser("""<node package="com.google.android.gms" />"""),
        )
    }

    @Test
    fun logContainsCredentialCancellation_detectsStackLine() {
        assertEquals(
            true,
            PreUSignInProbe.logContainsCredentialCancellation(
                "AuthManager E Google Sign-In failed\n" +
                    "androidx.credentials.exceptions.GetCredentialCancellationException: " +
                    "activity is cancelled by the user.",
            ),
        )
        assertEquals(
            false,
            PreUSignInProbe.logContainsCredentialCancellation("Silent sign-in failed"),
        )
    }
}

private fun probe(
    cancellationLogged: Boolean = false,
    signInUiShowing: Boolean = false,
    emptyHostOnly: Boolean = false,
    gotCredential: Boolean = false,
    accountChooserVisible: Boolean = false,
): PreUSignInProbe.Verdict = PreUSignInProbe.verdict(
    cancellationLogged = cancellationLogged,
    signInUiShowing = signInUiShowing,
    emptyHostOnly = emptyHostOnly,
    gotCredential = gotCredential,
    accountChooserVisible = accountChooserVisible,
)
