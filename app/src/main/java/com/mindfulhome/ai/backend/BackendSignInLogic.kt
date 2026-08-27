package com.mindfulhome.ai.backend

import com.mindfulhome.ai.AuthManagerLogic

/**
 * Pure mapping for Google → backend session outcomes. UI strings stay in
 * [backendSignInOutcomeMessage].
 */
object BackendSignInLogic {

    enum class MessageKind {
        None,
        DidNotComplete,
        NoAccount,
        PlayServices,
    }

    fun messageKind(outcome: BackendSignInOutcome): MessageKind = when (outcome) {
        is BackendSignInOutcome.Success,
        BackendSignInOutcome.InteractiveStarted,
        -> MessageKind.None
        BackendSignInOutcome.NoAccount -> MessageKind.NoAccount
        BackendSignInOutcome.PlayServicesUnavailable -> MessageKind.PlayServices
        BackendSignInOutcome.Cancelled,
        BackendSignInOutcome.ExchangeFailed,
        -> MessageKind.DidNotComplete
    }

    fun fromThrowable(throwable: Throwable): BackendSignInOutcome {
        if (AuthManagerLogic.isProviderConfigurationFailure(throwable)) {
            return BackendSignInOutcome.PlayServicesUnavailable
        }
        if (throwable.javaClass.name.endsWith("NoCredentialException")) {
            return BackendSignInOutcome.NoAccount
        }
        if (AuthManagerLogic.isGetCredentialCancellation(throwable)) {
            return BackendSignInOutcome.Cancelled
        }
        return BackendSignInOutcome.Cancelled
    }

    fun fromExchangeOk(ok: Boolean, email: String?): BackendSignInOutcome =
        if (ok) BackendSignInOutcome.Success(email) else BackendSignInOutcome.ExchangeFailed
}
