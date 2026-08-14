package com.mindfulhome.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthManagerLogicTest {

    @Test
    fun initialInteractiveStep_usesOneTapUnlessForcedPicker() {
        assertEquals(
            AuthManagerLogic.InteractiveGoogleSignInStep.OneTapAnyAccount,
            AuthManagerLogic.initialInteractiveStep(forceAccountPicker = false),
        )
        assertEquals(
            AuthManagerLogic.InteractiveGoogleSignInStep.SignInWithGoogleButton,
            AuthManagerLogic.initialInteractiveStep(forceAccountPicker = true),
        )
    }

    @Test
    fun fallbackInteractiveStep_oneTapFallsBackToSignInWithGoogle() {
        assertEquals(
            AuthManagerLogic.InteractiveGoogleSignInStep.SignInWithGoogleButton,
            AuthManagerLogic.fallbackInteractiveStep(
                AuthManagerLogic.InteractiveGoogleSignInStep.OneTapAnyAccount,
            ),
        )
        assertNull(
            AuthManagerLogic.fallbackInteractiveStep(
                AuthManagerLogic.InteractiveGoogleSignInStep.SignInWithGoogleButton,
            ),
        )
    }

    @Test
    fun isProviderConfigurationFailure_matchesClassNameOrMessage() {
        assertEquals(
            true,
            AuthManagerLogic.isProviderConfigurationFailure(
                GetCredentialProviderConfigurationException("missing"),
            ),
        )
        assertEquals(
            true,
            AuthManagerLogic.isProviderConfigurationFailure(
                RuntimeException(
                    "getCredentialAsync no provider dependencies found - please ensure the desired provider dependencies are added",
                ),
            ),
        )
        assertEquals(
            false,
            AuthManagerLogic.isProviderConfigurationFailure(RuntimeException("cancelled")),
        )
    }

    @Test
    fun shouldSkipSilentCredentialManager_preAndroid14() {
        assertEquals(true, AuthManagerLogic.shouldSkipSilentCredentialManager(29))
        assertEquals(true, AuthManagerLogic.shouldSkipSilentCredentialManager(33))
        assertEquals(false, AuthManagerLogic.shouldSkipSilentCredentialManager(34))
        assertEquals(false, AuthManagerLogic.shouldSkipSilentCredentialManager(36))
    }

    @Test
    fun interactiveSignInHandoff_consumesOnce() {
        AuthManagerLogic.InteractiveSignInHandoff.consumePersistedSuccess()
        assertEquals(false, AuthManagerLogic.InteractiveSignInHandoff.consumePersistedSuccess())
        AuthManagerLogic.InteractiveSignInHandoff.markPersistedSuccess()
        assertEquals(true, AuthManagerLogic.InteractiveSignInHandoff.consumePersistedSuccess())
        assertEquals(false, AuthManagerLogic.InteractiveSignInHandoff.consumePersistedSuccess())
    }

    @Test
    fun isGetCredentialCancellation_matchesFrameworkCancel() {
        assertEquals(
            true,
            AuthManagerLogic.isGetCredentialCancellation(
                RuntimeException("activity is cancelled by the user."),
            ),
        )
        assertEquals(
            true,
            AuthManagerLogic.isGetCredentialCancellation(
                GetCredentialCancellationException("activity is cancelled by the user."),
            ),
        )
        assertEquals(
            false,
            AuthManagerLogic.isGetCredentialCancellation(RuntimeException("no provider")),
        )
    }

    @Test
    fun preUSignInProbeVerdict_falseCancelWhenFrameworkReportsUserCancel() {
        assertEquals(
            AuthManagerLogic.PreUSignInProbeVerdict.FalseUserCancel,
            probe(cancellationLogged = true),
        )
    }

    @Test
    fun preUSignInProbeVerdict_uiStillShowingWhenChooserVisible() {
        assertEquals(
            AuthManagerLogic.PreUSignInProbeVerdict.UiStillShowing,
            probe(signInUiShowing = true, accountChooserVisible = true),
        )
    }

    @Test
    fun preUSignInProbeVerdict_emptyGisWhenPickerHasNoChooser() {
        assertEquals(
            AuthManagerLogic.PreUSignInProbeVerdict.EmptyGisUi,
            probe(signInUiShowing = true, accountChooserVisible = false),
        )
    }

    @Test
    fun preUSignInProbeVerdict_signedInWinsOverCancelLog() {
        assertEquals(
            AuthManagerLogic.PreUSignInProbeVerdict.SignedIn,
            probe(cancellationLogged = true, gotCredential = true),
        )
    }

    @Test
    fun preUSignInProbeVerdict_endedWithoutUiWhenHostDies() {
        assertEquals(
            AuthManagerLogic.PreUSignInProbeVerdict.EndedWithoutUi,
            probe(),
        )
    }

    @Test
    fun preUSignInProbeVerdict_emptyHostIsNotTheGooglePicker() {
        assertEquals(
            AuthManagerLogic.PreUSignInProbeVerdict.EmptyHostOnly,
            probe(emptyHostOnly = true),
        )
    }

    @Test
    fun dumpsysShowsEmptySignInHost_ignoresBareGoogleSignInActivity() {
        val dump = "mResumedActivity: ActivityRecord{abc u0 com.mindfulhome/.GoogleSignInActivity t12}"
        assertEquals(true, AuthManagerLogic.dumpsysShowsEmptySignInHost(dump))
        assertEquals(false, AuthManagerLogic.dumpsysShowsGoogleIdPicker(dump))
    }

    @Test
    fun dumpsysShowsGoogleIdPicker_matchesSignInHub() {
        val dump = "mResumedActivity: ActivityRecord{abc u0 com.google.android.gms/.auth.api.signin.ui.SignInHubActivity t9}"
        assertEquals(true, AuthManagerLogic.dumpsysShowsGoogleIdPicker(dump))
        assertEquals(false, AuthManagerLogic.dumpsysShowsEmptySignInHost(dump))
    }

    @Test
    fun dumpsysShowsGoogleIdPicker_matchesGmsAssistedSignIn() {
        val dump = "mResumedActivity: ActivityRecord{56a679d u0 com.google.android.gms/.auth.api.credentials.assistedsignin.ui.GoogleSignInActivity t19}"
        assertEquals(true, AuthManagerLogic.dumpsysShowsGoogleIdPicker(dump))
        assertEquals(false, AuthManagerLogic.dumpsysShowsEmptySignInHost(dump))
    }

    @Test
    fun windowDumpShowsAccountChooser_detectsChooseAnAccount() {
        assertEquals(
            true,
            AuthManagerLogic.windowDumpShowsAccountChooser(
                """<node text="Choose an account" />""",
            ),
        )
        assertEquals(
            false,
            AuthManagerLogic.windowDumpShowsAccountChooser("""<node package="com.google.android.gms" />"""),
        )
    }

    @Test
    fun logContainsCredentialCancellation_detectsStackLine() {
        assertEquals(
            true,
            AuthManagerLogic.logContainsCredentialCancellation(
                "AuthManager E Google Sign-In failed\n" +
                    "androidx.credentials.exceptions.GetCredentialCancellationException: " +
                    "activity is cancelled by the user.",
            ),
        )
        assertEquals(
            false,
            AuthManagerLogic.logContainsCredentialCancellation("Silent sign-in failed"),
        )
    }
}

private fun probe(
    cancellationLogged: Boolean = false,
    signInUiShowing: Boolean = false,
    emptyHostOnly: Boolean = false,
    gotCredential: Boolean = false,
    accountChooserVisible: Boolean = false,
): AuthManagerLogic.PreUSignInProbeVerdict = AuthManagerLogic.preUSignInProbeVerdict(
    cancellationLogged = cancellationLogged,
    signInUiShowing = signInUiShowing,
    emptyHostOnly = emptyHostOnly,
    gotCredential = gotCredential,
    accountChooserVisible = accountChooserVisible,
)

private class GetCredentialProviderConfigurationException(message: String) : RuntimeException(message)

private class GetCredentialCancellationException(message: String) : RuntimeException(message)
