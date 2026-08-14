package com.mindfulhome

/**
 * Pure helpers extracted from [MainActivity] for unit testing and CRAP reduction.
 */
object MainActivityLogic {

    const val AUTH_PREFLIGHT_THROTTLE_MS = 15_000L

    fun resolveStartDestination(
        onboardingDone: Boolean,
        shouldShowTimer: Boolean,
        timerIsRunning: Boolean,
        postTimerRoute: String,
    ): String = when {
        !onboardingDone -> "onboarding"
        shouldShowTimer -> "timer"
        timerIsRunning -> postTimerRoute
        else -> "default"
    }

    fun isLauncherHomeIntentFlags(
        actionIsMain: Boolean,
        hasHomeCategory: Boolean,
        openPrefill: Boolean,
        fromUnlock: Boolean,
        forceTimer: Boolean,
    ): Boolean {
        return actionIsMain &&
            hasHomeCategory &&
            !openPrefill &&
            !fromUnlock &&
            !forceTimer
    }

    /**
     * Decision tree for [MainActivity.handleIncomingIntent] once extras are read.
     * Side effects (prefs, navigation, session start) stay in the activity.
     */
    fun decideIncomingIntent(
        openPrefill: Boolean,
        prefillMinutes: Int,
        prefillReason: String?,
        isLauncherHome: Boolean,
        onboardingDone: Boolean,
        fromUnlock: Boolean,
        forceTimer: Boolean,
        forceTimerReason: String?,
        timerIsExpired: Boolean,
    ): IncomingIntentDecision {
        if (openPrefill) {
            return IncomingIntentDecision.OpenTimerPrefill(
                minutes = prefillMinutes.takeIf { it in 1..120 },
                reason = prefillReason.orEmpty(),
            )
        }
        if (!onboardingDone) {
            return IncomingIntentDecision.NoOp
        }
        if (isLauncherHome) {
            return IncomingIntentDecision.NavigateDefaultFromLauncherHome
        }
        if (forceTimer && forceTimerReason == MainActivity.FORCE_TIMER_REASON_EXPIRED) {
            if (!timerIsExpired) {
                return IncomingIntentDecision.IgnoreExpiredForce("expired_timer")
            }
        }
        if (forceTimer && forceTimerReason == MainActivity.FORCE_TIMER_REASON_QUICK_LAUNCH) {
            // QL exit no longer forces UI — birds stay in the foreground app.
            return IncomingIntentDecision.IgnoreExpiredForce(forceTimerReason)
        }
        if (fromUnlock || forceTimer) {
            return IncomingIntentDecision.NavigateUnlockOrForce(
                fromUnlock = fromUnlock,
                forceTimer = forceTimer,
                destination = "default",
            )
        }
        return IncomingIntentDecision.NoOp
    }

    fun usageAccessCovered(hasUsageAccess: Boolean, hasAccessibility: Boolean): Boolean =
        hasUsageAccess || hasAccessibility

    fun nextMissingPermission(
        hasNotifications: Boolean,
        hasUsageAccess: Boolean,
        hasOverlay: Boolean,
        notificationsSuppressed: Boolean,
        usageSuppressed: Boolean,
        overlaySuppressed: Boolean,
        hasAccessibility: Boolean = false,
    ): MissingPermissionKind? = when {
        !hasNotifications && !notificationsSuppressed -> MissingPermissionKind.Notifications
        !usageAccessCovered(hasUsageAccess, hasAccessibility) && !usageSuppressed ->
            MissingPermissionKind.UsageAccess
        !hasOverlay && !overlaySuppressed -> MissingPermissionKind.Overlay
        else -> null
    }

    /** Which permission prompts should clear suppression because the permission is now granted. */
    fun permissionSuppressionsToClear(
        hasNotifications: Boolean,
        hasUsageAccess: Boolean,
        hasOverlay: Boolean,
    ): List<MissingPermissionKind> = buildList {
        if (hasNotifications) add(MissingPermissionKind.Notifications)
        if (hasUsageAccess) add(MissingPermissionKind.UsageAccess)
        if (hasOverlay) add(MissingPermissionKind.Overlay)
    }

    fun shouldSkipPermissionPrompt(
        dialogShowing: Boolean,
        finishing: Boolean,
        destroyed: Boolean,
        onboardingDone: Boolean,
    ): Boolean = dialogShowing || finishing || destroyed || !onboardingDone

    data class PermissionDialogCopy(
        val title: String,
        val message: String,
    )

    fun permissionDialogCopy(kind: MissingPermissionKind): PermissionDialogCopy = when (kind) {
        MissingPermissionKind.Notifications -> PermissionDialogCopy(
            title = "Allow notifications",
            message = "MindfulHome needs notification permission to show timer and nudge alerts.",
        )
        MissingPermissionKind.UsageAccess -> PermissionDialogCopy(
            title = "Grant Usage Access",
            message = "Without app-switch detection, MindfulHome needs Usage Access to detect your foreground app for timer and karma tracking.",
        )
        MissingPermissionKind.Overlay -> PermissionDialogCopy(
            title = "Allow overlay permission",
            message = "MindfulHome can show nudge overlays and chat heads over apps. Without it, only notifications are used.",
        )
    }

    fun onResumeShouldClearTimerFlag(quickLaunchSessionActive: Boolean, shouldShowTimer: Boolean): Boolean =
        quickLaunchSessionActive && shouldShowTimer

    sealed class OnResumeBackgroundAction {
        data object SkipOnboarding : OnResumeBackgroundAction()
        data object StayOnQuickLaunch : OnResumeBackgroundAction()
        data class Navigate(val destination: String, val logQuickReturn: Boolean) : OnResumeBackgroundAction()
    }

    fun decideOnResumeBackground(
        onboardingDone: Boolean,
        destination: String?,
    ): OnResumeBackgroundAction = when {
        !onboardingDone -> OnResumeBackgroundAction.SkipOnboarding
        destination == null -> OnResumeBackgroundAction.StayOnQuickLaunch
        destination == "home" -> OnResumeBackgroundAction.Navigate(destination, logQuickReturn = true)
        else -> OnResumeBackgroundAction.Navigate(destination, logQuickReturn = false)
    }

    fun authPreflightLogMessage(result: AuthPreflightResult): String? = when (result) {
        AuthPreflightResult.HealthySession -> null
        is AuthPreflightResult.Refreshed -> {
            if (result.ok) null
            else "Backend preflight: refresh skipped or failed; will retry later"
        }
        AuthPreflightResult.NoSilentAccount ->
            "Backend preflight: no pre-authorized account, skipping"
        is AuthPreflightResult.SignedIn -> {
            if (result.ok) null
            else "Backend preflight: exchange rejected, will retry on next AI call"
        }
    }

    fun authPreflightLogIsWarning(result: AuthPreflightResult): Boolean =
        result is AuthPreflightResult.SignedIn && !result.ok

    /**
     * Gate for starting backend auth preflight (clock + mode + in-progress).
     * Returns null when the coroutine should not start.
     */
    fun authPreflightGate(
        inProgress: Boolean,
        isBackendMode: Boolean,
        nowMs: Long,
        lastAttemptMs: Long,
        throttleMs: Long = AUTH_PREFLIGHT_THROTTLE_MS,
    ): AuthPreflightGate {
        if (inProgress) return AuthPreflightGate.Skip(AuthPreflightSkipReason.InProgress)
        if (!isBackendMode) return AuthPreflightGate.Skip(AuthPreflightSkipReason.NotBackendMode)
        if (nowMs - lastAttemptMs < throttleMs) {
            return AuthPreflightGate.Skip(AuthPreflightSkipReason.Throttled)
        }
        return AuthPreflightGate.Start(updatedLastAttemptMs = nowMs)
    }

    /**
     * Session / silent-sign-in state machine. Ports are injected so unit tests never
     * touch [com.mindfulhome.ai.backend.AuthManager] or real network.
     */
    suspend fun runAuthPreflight(
        hasSession: Boolean,
        sessionNearingExpiry: Boolean,
        signInSilent: suspend () -> AuthPreflightSignIn?,
        refreshIfNeeded: suspend () -> Boolean,
        completeBackendSignIn: suspend (idToken: String) -> Boolean,
        saveSignedInEmail: suspend (String) -> Unit,
    ): AuthPreflightResult {
        if (hasSession && !sessionNearingExpiry) {
            return AuthPreflightResult.HealthySession
        }
        if (hasSession && sessionNearingExpiry) {
            val ok = refreshIfNeeded()
            return AuthPreflightResult.Refreshed(ok)
        }
        val signIn = signInSilent()
            ?: return AuthPreflightResult.NoSilentAccount
        signIn.email?.let { saveSignedInEmail(it) }
        val ok = completeBackendSignIn(signIn.idToken)
        return AuthPreflightResult.SignedIn(ok = ok, email = signIn.email)
    }
}

enum class MissingPermissionKind {
    Notifications,
    UsageAccess,
    Overlay,
}

sealed class IncomingIntentDecision {
    data class OpenTimerPrefill(
        val minutes: Int?,
        val reason: String,
    ) : IncomingIntentDecision()

    data object NavigateDefaultFromLauncherHome : IncomingIntentDecision()

    data class IgnoreExpiredForce(val reason: String) : IncomingIntentDecision()


    data class NavigateUnlockOrForce(
        val fromUnlock: Boolean,
        val forceTimer: Boolean,
        val destination: String,
    ) : IncomingIntentDecision()

    data object NoOp : IncomingIntentDecision()
}

enum class AuthPreflightSkipReason {
    InProgress,
    NotBackendMode,
    Throttled,
}

sealed class AuthPreflightGate {
    data class Skip(val reason: AuthPreflightSkipReason) : AuthPreflightGate()
    data class Start(val updatedLastAttemptMs: Long) : AuthPreflightGate()
}

data class AuthPreflightSignIn(val idToken: String, val email: String?)

sealed class AuthPreflightResult {
    data object HealthySession : AuthPreflightResult()
    data class Refreshed(val ok: Boolean) : AuthPreflightResult()
    data object NoSilentAccount : AuthPreflightResult()
    data class SignedIn(val ok: Boolean, val email: String?) : AuthPreflightResult()
}
