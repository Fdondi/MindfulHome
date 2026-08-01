package com.mindfulhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class MainActivityLogicTest {

    @Test
    fun resolveStartDestination_priorityOrder() {
        assertEquals(
            "onboarding",
            MainActivityLogic.resolveStartDestination(
                onboardingDone = false,
                shouldShowTimer = true,
                timerIsRunning = true,
                postTimerRoute = "home",
            ),
        )
        assertEquals(
            "timer",
            MainActivityLogic.resolveStartDestination(
                onboardingDone = true,
                shouldShowTimer = true,
                timerIsRunning = true,
                postTimerRoute = "home",
            ),
        )
        assertEquals(
            "assistant",
            MainActivityLogic.resolveStartDestination(
                onboardingDone = true,
                shouldShowTimer = false,
                timerIsRunning = true,
                postTimerRoute = "assistant",
            ),
        )
        assertEquals(
            "default",
            MainActivityLogic.resolveStartDestination(
                onboardingDone = true,
                shouldShowTimer = false,
                timerIsRunning = false,
                postTimerRoute = "home",
            ),
        )
    }

    @Test
    fun isLauncherHomeIntentFlags_requiresMainHomeWithoutOverrides() {
        assertTrue(
            MainActivityLogic.isLauncherHomeIntentFlags(
                actionIsMain = true,
                hasHomeCategory = true,
                openPrefill = false,
                fromUnlock = false,
                forceTimer = false,
            ),
        )
        assertEquals(
            false,
            MainActivityLogic.isLauncherHomeIntentFlags(
                actionIsMain = true,
                hasHomeCategory = true,
                openPrefill = true,
                fromUnlock = false,
                forceTimer = false,
            ),
        )
    }

    @Test
    fun decideIncomingIntent_openPrefillClampsMinutes() {
        val decision = MainActivityLogic.decideIncomingIntent(
            openPrefill = true,
            prefillMinutes = 200,
            prefillReason = "todo",
            isLauncherHome = false,
            onboardingDone = true,
            fromUnlock = false,
            forceTimer = false,
            forceTimerReason = null,
            timerIsExpired = false,
        )
        assertEquals(
            IncomingIntentDecision.OpenTimerPrefill(minutes = null, reason = "todo"),
            decision,
        )
    }

    @Test
    fun decideIncomingIntent_openPrefillValidMinutes() {
        val decision = MainActivityLogic.decideIncomingIntent(
            openPrefill = true,
            prefillMinutes = 15,
            prefillReason = null,
            isLauncherHome = true,
            onboardingDone = false,
            fromUnlock = true,
            forceTimer = true,
            forceTimerReason = "x",
            timerIsExpired = true,
        )
        assertEquals(
            IncomingIntentDecision.OpenTimerPrefill(minutes = 15, reason = ""),
            decision,
        )
    }

    @Test
    fun decideIncomingIntent_launcherHomeRespectsOnboarding() {
        assertEquals(
            IncomingIntentDecision.NavigateDefaultFromLauncherHome,
            MainActivityLogic.decideIncomingIntent(
                openPrefill = false,
                prefillMinutes = -1,
                prefillReason = null,
                isLauncherHome = true,
                onboardingDone = true,
                fromUnlock = false,
                forceTimer = false,
                forceTimerReason = null,
                timerIsExpired = false,
            ),
        )
        assertEquals(
            IncomingIntentDecision.NoOp,
            MainActivityLogic.decideIncomingIntent(
                openPrefill = false,
                prefillMinutes = -1,
                prefillReason = null,
                isLauncherHome = true,
                onboardingDone = false,
                fromUnlock = false,
                forceTimer = false,
                forceTimerReason = null,
                timerIsExpired = false,
            ),
        )
    }

    @Test
    fun decideIncomingIntent_expiredForceIgnoredWhenNotExpired() {
        val decision = MainActivityLogic.decideIncomingIntent(
            openPrefill = false,
            prefillMinutes = -1,
            prefillReason = null,
            isLauncherHome = false,
            onboardingDone = true,
            fromUnlock = false,
            forceTimer = true,
            forceTimerReason = MainActivity.FORCE_TIMER_REASON_EXPIRED,
            timerIsExpired = false,
        )
        assertEquals(IncomingIntentDecision.IgnoreExpiredForce("expired_timer"), decision)
    }

    @Test
    fun decideIncomingIntent_expiredForceWhenExpiredGoesToDefault() {
        val decision = MainActivityLogic.decideIncomingIntent(
            openPrefill = false,
            prefillMinutes = -1,
            prefillReason = null,
            isLauncherHome = false,
            onboardingDone = true,
            fromUnlock = false,
            forceTimer = true,
            forceTimerReason = MainActivity.FORCE_TIMER_REASON_EXPIRED,
            timerIsExpired = true,
        )
        assertEquals(
            IncomingIntentDecision.NavigateUnlockOrForce(
                fromUnlock = false,
                forceTimer = true,
                destination = "default",
            ),
            decision,
        )
    }

    @Test
    fun decideIncomingIntent_shouldYouBeHereRequiresExpired() {
        assertEquals(
            IncomingIntentDecision.IgnoreExpiredForce(MainActivity.FORCE_TIMER_REASON_QUICK_LAUNCH),
            MainActivityLogic.decideIncomingIntent(
                openPrefill = false,
                prefillMinutes = -1,
                prefillReason = null,
                isLauncherHome = false,
                onboardingDone = true,
                fromUnlock = false,
                forceTimer = true,
                forceTimerReason = MainActivity.FORCE_TIMER_REASON_QUICK_LAUNCH,
                timerIsExpired = false,
            ),
        )
        assertEquals(
            IncomingIntentDecision.NavigateShouldYouBeHere,
            MainActivityLogic.decideIncomingIntent(
                openPrefill = false,
                prefillMinutes = -1,
                prefillReason = null,
                isLauncherHome = false,
                onboardingDone = true,
                fromUnlock = false,
                forceTimer = true,
                forceTimerReason = MainActivity.FORCE_TIMER_REASON_SHOULD_YOU_BE_HERE,
                timerIsExpired = true,
            ),
        )
    }

    @Test
    fun decideIncomingIntent_unlockNavigatesDefault() {
        assertEquals(
            IncomingIntentDecision.NavigateUnlockOrForce(
                fromUnlock = true,
                forceTimer = false,
                destination = "default",
            ),
            MainActivityLogic.decideIncomingIntent(
                openPrefill = false,
                prefillMinutes = -1,
                prefillReason = null,
                isLauncherHome = false,
                onboardingDone = true,
                fromUnlock = true,
                forceTimer = false,
                forceTimerReason = null,
                timerIsExpired = false,
            ),
        )
    }

    @Test
    fun nextMissingPermission_priorityAndSuppression() {
        assertEquals(
            MissingPermissionKind.Notifications,
            MainActivityLogic.nextMissingPermission(
                hasNotifications = false,
                hasUsageAccess = false,
                hasOverlay = false,
                notificationsSuppressed = false,
                usageSuppressed = false,
                overlaySuppressed = false,
            ),
        )
        assertEquals(
            MissingPermissionKind.UsageAccess,
            MainActivityLogic.nextMissingPermission(
                hasNotifications = false,
                hasUsageAccess = false,
                hasOverlay = false,
                notificationsSuppressed = true,
                usageSuppressed = false,
                overlaySuppressed = false,
            ),
        )
        assertEquals(
            MissingPermissionKind.Overlay,
            MainActivityLogic.nextMissingPermission(
                hasNotifications = true,
                hasUsageAccess = true,
                hasOverlay = false,
                notificationsSuppressed = false,
                usageSuppressed = false,
                overlaySuppressed = false,
            ),
        )
        assertNull(
            MainActivityLogic.nextMissingPermission(
                hasNotifications = true,
                hasUsageAccess = true,
                hasOverlay = true,
                notificationsSuppressed = false,
                usageSuppressed = false,
                overlaySuppressed = false,
            ),
        )
        assertNull(
            MainActivityLogic.nextMissingPermission(
                hasNotifications = false,
                hasUsageAccess = false,
                hasOverlay = false,
                notificationsSuppressed = true,
                usageSuppressed = true,
                overlaySuppressed = true,
            ),
        )
    }

    @Test
    fun authPreflightGate_skipsAndStarts() {
        assertEquals(
            AuthPreflightGate.Skip(AuthPreflightSkipReason.InProgress),
            MainActivityLogic.authPreflightGate(
                inProgress = true,
                isBackendMode = true,
                nowMs = 100L,
                lastAttemptMs = 0L,
            ),
        )
        assertEquals(
            AuthPreflightGate.Skip(AuthPreflightSkipReason.NotBackendMode),
            MainActivityLogic.authPreflightGate(
                inProgress = false,
                isBackendMode = false,
                nowMs = 100L,
                lastAttemptMs = 0L,
            ),
        )
        assertEquals(
            AuthPreflightGate.Skip(AuthPreflightSkipReason.Throttled),
            MainActivityLogic.authPreflightGate(
                inProgress = false,
                isBackendMode = true,
                nowMs = 10_000L,
                lastAttemptMs = 0L,
            ),
        )
        assertEquals(
            AuthPreflightGate.Start(updatedLastAttemptMs = 20_000L),
            MainActivityLogic.authPreflightGate(
                inProgress = false,
                isBackendMode = true,
                nowMs = 20_000L,
                lastAttemptMs = 0L,
            ),
        )
    }

    @Test
    fun runAuthPreflight_healthySessionSkipsPorts() = runBlocking {
        var signInCalls = 0
        var refreshCalls = 0
        val result = MainActivityLogic.runAuthPreflight(
            hasSession = true,
            sessionNearingExpiry = false,
            signInSilent = {
                signInCalls++
                null
            },
            refreshIfNeeded = {
                refreshCalls++
                true
            },
            completeBackendSignIn = { error("no exchange") },
            saveSignedInEmail = { error("no email") },
        )
        assertEquals(AuthPreflightResult.HealthySession, result)
        assertEquals(0, signInCalls)
        assertEquals(0, refreshCalls)
    }

    @Test
    fun runAuthPreflight_refreshPath() = runBlocking {
        var refreshCalls = 0
        val result = MainActivityLogic.runAuthPreflight(
            hasSession = true,
            sessionNearingExpiry = true,
            signInSilent = { error("no google") },
            refreshIfNeeded = {
                refreshCalls++
                false
            },
            completeBackendSignIn = { error("no exchange") },
            saveSignedInEmail = { error("no email") },
        )
        assertEquals(AuthPreflightResult.Refreshed(ok = false), result)
        assertEquals(1, refreshCalls)
    }

    @Test
    fun runAuthPreflight_noSilentAccount() = runBlocking {
        val result = MainActivityLogic.runAuthPreflight(
            hasSession = false,
            sessionNearingExpiry = false,
            signInSilent = { null },
            refreshIfNeeded = { error("no refresh") },
            completeBackendSignIn = { error("no exchange") },
            saveSignedInEmail = { error("no email") },
        )
        assertEquals(AuthPreflightResult.NoSilentAccount, result)
    }

    @Test
    fun runAuthPreflight_silentSignInSuccess() = runBlocking {
        var savedEmail: String? = null
        var exchangedToken: String? = null
        val result = MainActivityLogic.runAuthPreflight(
            hasSession = false,
            sessionNearingExpiry = false,
            signInSilent = { AuthPreflightSignIn(idToken = "tok", email = "a@b.c") },
            refreshIfNeeded = { error("no refresh") },
            completeBackendSignIn = { token ->
                exchangedToken = token
                true
            },
            saveSignedInEmail = { savedEmail = it },
        )
        assertEquals(AuthPreflightResult.SignedIn(ok = true, email = "a@b.c"), result)
        assertEquals("a@b.c", savedEmail)
        assertEquals("tok", exchangedToken)
    }

    @Test
    fun runAuthPreflight_silentSignInNullEmailStillExchanges() = runBlocking {
        var emailSaves = 0
        val result = MainActivityLogic.runAuthPreflight(
            hasSession = false,
            sessionNearingExpiry = false,
            signInSilent = { AuthPreflightSignIn(idToken = "tok2", email = null) },
            refreshIfNeeded = { error("no refresh") },
            completeBackendSignIn = { false },
            saveSignedInEmail = { emailSaves++ },
        )
        assertEquals(AuthPreflightResult.SignedIn(ok = false, email = null), result)
        assertEquals(0, emailSaves)
    }

    @Test
    fun permissionSuppressionsToClear_andSkipGate() {
        assertEquals(
            listOf(
                MissingPermissionKind.Notifications,
                MissingPermissionKind.UsageAccess,
                MissingPermissionKind.Overlay,
            ),
            MainActivityLogic.permissionSuppressionsToClear(
                hasNotifications = true,
                hasUsageAccess = true,
                hasOverlay = true,
            ),
        )
        assertEquals(
            emptyList<MissingPermissionKind>(),
            MainActivityLogic.permissionSuppressionsToClear(false, false, false),
        )
        assertTrue(
            MainActivityLogic.shouldSkipPermissionPrompt(
                dialogShowing = false,
                finishing = false,
                destroyed = false,
                onboardingDone = false,
            ),
        )
        assertEquals(
            false,
            MainActivityLogic.shouldSkipPermissionPrompt(
                dialogShowing = false,
                finishing = false,
                destroyed = false,
                onboardingDone = true,
            ),
        )
    }

    @Test
    fun authPreflightLogMessage_variants() {
        assertEquals(null, MainActivityLogic.authPreflightLogMessage(AuthPreflightResult.HealthySession))
        assertEquals(null, MainActivityLogic.authPreflightLogMessage(AuthPreflightResult.Refreshed(true)))
        assertTrue(
            MainActivityLogic.authPreflightLogMessage(AuthPreflightResult.Refreshed(false))!!
                .contains("refresh"),
        )
        assertTrue(
            MainActivityLogic.authPreflightLogMessage(AuthPreflightResult.NoSilentAccount)!!
                .contains("no pre-authorized"),
        )
        assertEquals(
            null,
            MainActivityLogic.authPreflightLogMessage(AuthPreflightResult.SignedIn(true, "a@b.c")),
        )
        assertTrue(
            MainActivityLogic.authPreflightLogIsWarning(AuthPreflightResult.SignedIn(false, null)),
        )
        assertEquals(
            false,
            MainActivityLogic.authPreflightLogIsWarning(AuthPreflightResult.Refreshed(false)),
        )
    }

    @Test
    fun permissionDialogCopy_andOnResumeHelpers() {
        assertEquals("Allow notifications", MainActivityLogic.permissionDialogCopy(MissingPermissionKind.Notifications).title)
        assertTrue(MainActivityLogic.permissionDialogCopy(MissingPermissionKind.UsageAccess).message.contains("Usage Access"))
        assertTrue(MainActivityLogic.permissionDialogCopy(MissingPermissionKind.Overlay).message.contains("overlay"))
        assertTrue(MainActivityLogic.onResumeShouldClearTimerFlag(true, true))
        assertFalse(MainActivityLogic.onResumeShouldClearTimerFlag(false, true))
        assertEquals(
            MainActivityLogic.OnResumeBackgroundAction.SkipOnboarding,
            MainActivityLogic.decideOnResumeBackground(onboardingDone = false, destination = "home"),
        )
        assertEquals(
            MainActivityLogic.OnResumeBackgroundAction.StayOnQuickLaunch,
            MainActivityLogic.decideOnResumeBackground(onboardingDone = true, destination = null),
        )
        val home = MainActivityLogic.decideOnResumeBackground(true, "home")
            as MainActivityLogic.OnResumeBackgroundAction.Navigate
        assertTrue(home.logQuickReturn)
        val other = MainActivityLogic.decideOnResumeBackground(true, "timer")
            as MainActivityLogic.OnResumeBackgroundAction.Navigate
        assertFalse(other.logQuickReturn)
    }
}
