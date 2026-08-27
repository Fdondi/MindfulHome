package com.mindfulhome.ai.backend

import org.junit.Assert.assertEquals
import org.junit.Test

class BackendSignInLogicTest {

    @Test
    fun messageKind_isSilentForSuccessAndInteractiveStart() {
        assertEquals(
            BackendSignInLogic.MessageKind.None,
            BackendSignInLogic.messageKind(BackendSignInOutcome.Success("a@b.c")),
        )
        assertEquals(
            BackendSignInLogic.MessageKind.None,
            BackendSignInLogic.messageKind(BackendSignInOutcome.InteractiveStarted),
        )
    }

    @Test
    fun messageKind_mapsFailuresToToastKinds() {
        assertEquals(
            BackendSignInLogic.MessageKind.NoAccount,
            BackendSignInLogic.messageKind(BackendSignInOutcome.NoAccount),
        )
        assertEquals(
            BackendSignInLogic.MessageKind.PlayServices,
            BackendSignInLogic.messageKind(BackendSignInOutcome.PlayServicesUnavailable),
        )
        assertEquals(
            BackendSignInLogic.MessageKind.DidNotComplete,
            BackendSignInLogic.messageKind(BackendSignInOutcome.Cancelled),
        )
        assertEquals(
            BackendSignInLogic.MessageKind.DidNotComplete,
            BackendSignInLogic.messageKind(BackendSignInOutcome.ExchangeFailed),
        )
    }

    @Test
    fun fromThrowable_matchesAuthManagerClassNames() {
        assertEquals(
            BackendSignInOutcome.PlayServicesUnavailable,
            BackendSignInLogic.fromThrowable(GetCredentialProviderConfigurationException("missing")),
        )
        assertEquals(
            BackendSignInOutcome.NoAccount,
            BackendSignInLogic.fromThrowable(NoCredentialException("none")),
        )
        assertEquals(
            BackendSignInOutcome.Cancelled,
            BackendSignInLogic.fromThrowable(
                GetCredentialCancellationException("activity is cancelled by the user"),
            ),
        )
        assertEquals(
            BackendSignInOutcome.Cancelled,
            BackendSignInLogic.fromThrowable(RuntimeException("other")),
        )
    }

    @Test
    fun fromExchangeOk_successKeepsEmail() {
        assertEquals(
            BackendSignInOutcome.Success("a@b.c"),
            BackendSignInLogic.fromExchangeOk(true, "a@b.c"),
        )
        assertEquals(
            BackendSignInOutcome.ExchangeFailed,
            BackendSignInLogic.fromExchangeOk(false, "a@b.c"),
        )
    }
}

private class GetCredentialProviderConfigurationException(message: String) : Exception(message)
private class NoCredentialException(message: String) : Exception(message)
private class GetCredentialCancellationException(message: String) : Exception(message)
