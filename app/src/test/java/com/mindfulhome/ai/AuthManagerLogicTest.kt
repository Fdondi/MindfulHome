package com.mindfulhome.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}

private class GetCredentialProviderConfigurationException(message: String) : RuntimeException(message)

private class GetCredentialCancellationException(message: String) : RuntimeException(message)
