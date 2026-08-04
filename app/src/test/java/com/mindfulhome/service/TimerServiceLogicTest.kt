package com.mindfulhome.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

class TimerServiceLogicTest {

    // --- decideQuickLaunchSwitch ---

    @Test
    fun decideQuickLaunchSwitch_allowedWhenInAllowlist() {
        val decision = decideQuickLaunchSwitch(
            packageName = "com.allowed",
            previousPackage = "com.other",
            selfPackageName = "com.mindfulhome",
            allowedPackages = setOf("com.allowed", "com.mindfulhome"),
            utilityReason = null,
            previousIsUtilityOrSystem = false,
            awaitHomeSettleAfterGateLeave = true,
            currentExitCandidatePackage = null,
            resume = null,
            nowMs = 1_000L,
        )
        assertEquals(QuickLaunchSwitchDecision.Allowed, decision)
    }

    @Test
    fun decideQuickLaunchSwitch_utilityAwaitingHome_isIgnoreAwaiting() {
        val decision = decideQuickLaunchSwitch(
            packageName = "com.android.systemui",
            previousPackage = "com.allowed",
            selfPackageName = "com.mindfulhome",
            allowedPackages = setOf("com.allowed"),
            utilityReason = "system_ui",
            previousIsUtilityOrSystem = false,
            awaitHomeSettleAfterGateLeave = true,
            currentExitCandidatePackage = null,
            resume = null,
            nowMs = 1_000L,
        )
        assertEquals(
            QuickLaunchSwitchDecision.Ignore(reason = "system_ui", awaitingHome = true),
            decision,
        )
    }

    @Test
    fun decideQuickLaunchSwitch_utilityNotAwaiting_isIgnore() {
        val decision = decideQuickLaunchSwitch(
            packageName = "com.android.systemui",
            previousPackage = "com.allowed",
            selfPackageName = "com.mindfulhome",
            allowedPackages = setOf("com.allowed"),
            utilityReason = "system_ui",
            previousIsUtilityOrSystem = false,
            awaitHomeSettleAfterGateLeave = false,
            currentExitCandidatePackage = null,
            resume = null,
            nowMs = 1_000L,
        )
        assertEquals(
            QuickLaunchSwitchDecision.Ignore(reason = "system_ui", awaitingHome = false),
            decision,
        )
    }

    @Test
    fun decideQuickLaunchSwitch_restrictedAwaitingHome_isIgnore() {
        val decision = decideQuickLaunchSwitch(
            packageName = "com.restricted",
            previousPackage = "com.restricted",
            selfPackageName = "com.mindfulhome",
            allowedPackages = setOf("com.allowed"),
            utilityReason = null,
            previousIsUtilityOrSystem = false,
            awaitHomeSettleAfterGateLeave = true,
            currentExitCandidatePackage = null,
            resume = null,
            nowMs = 1_000L,
        )
        assertEquals(
            QuickLaunchSwitchDecision.Ignore(reason = "awaiting_home_settle", awaitingHome = true),
            decision,
        )
    }

    @Test
    fun decideQuickLaunchSwitch_fromLauncher_instantGate() {
        val decision = decideQuickLaunchSwitch(
            packageName = "com.restricted",
            previousPackage = "com.mindfulhome",
            selfPackageName = "com.mindfulhome",
            allowedPackages = setOf("com.allowed", "com.mindfulhome"),
            utilityReason = null,
            previousIsUtilityOrSystem = false,
            awaitHomeSettleAfterGateLeave = false,
            currentExitCandidatePackage = null,
            resume = null,
            nowMs = 1_000L,
        )
        assertTrue(decision is QuickLaunchSwitchDecision.InstantGate)
        val gate = decision as QuickLaunchSwitchDecision.InstantGate
        assertTrue(gate.fromLauncherOrRecents)
        assertFalse(gate.graceAlreadyUsedUp)
        assertEquals(null, gate.resume)
    }

    @Test
    fun decideQuickLaunchSwitch_fromBlankPrevious_instantGate() {
        val decision = decideQuickLaunchSwitch(
            packageName = "com.restricted",
            previousPackage = "",
            selfPackageName = "com.mindfulhome",
            allowedPackages = setOf("com.allowed"),
            utilityReason = null,
            previousIsUtilityOrSystem = false,
            awaitHomeSettleAfterGateLeave = false,
            currentExitCandidatePackage = null,
            resume = null,
            nowMs = 1_000L,
        )
        assertTrue((decision as QuickLaunchSwitchDecision.InstantGate).fromLauncherOrRecents)
    }

    @Test
    fun decideQuickLaunchSwitch_fromUtilityPrevious_instantGate() {
        val decision = decideQuickLaunchSwitch(
            packageName = "com.restricted",
            previousPackage = "com.android.systemui",
            selfPackageName = "com.mindfulhome",
            allowedPackages = setOf("com.allowed"),
            utilityReason = null,
            previousIsUtilityOrSystem = true,
            awaitHomeSettleAfterGateLeave = false,
            currentExitCandidatePackage = null,
            resume = null,
            nowMs = 1_000L,
        )
        assertTrue((decision as QuickLaunchSwitchDecision.InstantGate).fromLauncherOrRecents)
    }

    @Test
    fun decideQuickLaunchSwitch_fromAllowlistedUtilityPrevious_startsGrace() {
        val decision = decideQuickLaunchSwitch(
            packageName = "com.restricted",
            previousPackage = "com.android.settings",
            selfPackageName = "com.mindfulhome",
            allowedPackages = setOf("com.android.settings", "com.mindfulhome"),
            utilityReason = null,
            previousIsUtilityOrSystem = true,
            awaitHomeSettleAfterGateLeave = false,
            currentExitCandidatePackage = null,
            resume = null,
            nowMs = 1_000L,
        )
        assertEquals(QuickLaunchSwitchDecision.StartGrace, decision)
    }

    @Test
    fun decideQuickLaunchSwitch_graceUsedUp_instantGateWithResume() {
        val resume = QuickLaunchExitResumeSnapshot(deadlineMs = 500L, phaseMs = 20_000L, karmaScore = -2)
        val decision = decideQuickLaunchSwitch(
            packageName = "com.restricted",
            previousPackage = "com.allowed",
            selfPackageName = "com.mindfulhome",
            allowedPackages = setOf("com.allowed"),
            utilityReason = null,
            previousIsUtilityOrSystem = false,
            awaitHomeSettleAfterGateLeave = false,
            currentExitCandidatePackage = null,
            resume = resume,
            nowMs = 1_000L,
        )
        val gate = decision as QuickLaunchSwitchDecision.InstantGate
        assertTrue(gate.graceAlreadyUsedUp)
        assertFalse(gate.fromLauncherOrRecents)
        assertEquals(resume, gate.resume)
    }

    @Test
    fun decideQuickLaunchSwitch_newCandidate_startGrace() {
        val decision = decideQuickLaunchSwitch(
            packageName = "com.restricted",
            previousPackage = "com.allowed",
            selfPackageName = "com.mindfulhome",
            allowedPackages = setOf("com.allowed"),
            utilityReason = null,
            previousIsUtilityOrSystem = false,
            awaitHomeSettleAfterGateLeave = false,
            currentExitCandidatePackage = null,
            resume = QuickLaunchExitResumeSnapshot(deadlineMs = 5_000L, phaseMs = 20_000L, karmaScore = 0),
            nowMs = 1_000L,
        )
        assertEquals(QuickLaunchSwitchDecision.StartGrace, decision)
    }

    @Test
    fun decideQuickLaunchSwitch_sameCandidate_continueMonitor() {
        val decision = decideQuickLaunchSwitch(
            packageName = "com.restricted",
            previousPackage = "com.allowed",
            selfPackageName = "com.mindfulhome",
            allowedPackages = setOf("com.allowed"),
            utilityReason = null,
            previousIsUtilityOrSystem = false,
            awaitHomeSettleAfterGateLeave = false,
            currentExitCandidatePackage = "com.restricted",
            resume = null,
            nowMs = 1_000L,
        )
        assertEquals(QuickLaunchSwitchDecision.ContinueMonitor, decision)
    }

    // --- computeQuickLaunchGraceTiming ---

    @Test
    fun computeQuickLaunchGraceTiming_optedOutUsesNormal() {
        val timing = computeQuickLaunchGraceTiming(
            karmaScore = -5,
            isOptedOut = true,
            normalPhaseMs = 20_000L,
            positiveMultiplier = 2f,
        )
        assertEquals(20_000L, timing.phaseMs)
        assertEquals(60_000L, timing.graceMs)
    }

    @Test
    fun computeQuickLaunchGraceTiming_zeroKarmaUsesNormal() {
        val timing = computeQuickLaunchGraceTiming(
            karmaScore = 0,
            isOptedOut = false,
            normalPhaseMs = 20_000L,
            positiveMultiplier = 1.5f,
        )
        assertEquals(20_000L, timing.phaseMs)
        assertEquals(60_000L, timing.graceMs)
    }

    @Test
    fun computeQuickLaunchGraceTiming_positiveKarmaScalesPhase() {
        val timing = computeQuickLaunchGraceTiming(
            karmaScore = 3,
            isOptedOut = false,
            normalPhaseMs = 20_000L,
            positiveMultiplier = 1.5f,
        )
        assertEquals(30_000L, timing.phaseMs)
        assertEquals(90_000L, timing.graceMs)
    }

    @Test
    fun computeQuickLaunchGraceTiming_positiveKarmaFloorAt5s() {
        val timing = computeQuickLaunchGraceTiming(
            karmaScore = 1,
            isOptedOut = false,
            normalPhaseMs = 1_000L,
            positiveMultiplier = 1f,
        )
        assertEquals(5_000L, timing.phaseMs)
        assertEquals(15_000L, timing.graceMs)
    }

    @Test
    fun computeQuickLaunchGraceTiming_negativeKarmaShortensGrace() {
        // base 60s / |karma|=3 → 20s grace; phase = 20s/3 ≥ 1s
        val timing = computeQuickLaunchGraceTiming(
            karmaScore = -3,
            isOptedOut = false,
            normalPhaseMs = 20_000L,
            positiveMultiplier = 1f,
        )
        assertEquals(20_000L, timing.graceMs)
        assertEquals(6_666L, timing.phaseMs)
    }

    // --- decideQuickLaunchGraceResume ---

    @Test
    fun decideQuickLaunchGraceResume_null_configureNew() {
        assertEquals(
            QuickLaunchGraceResumeAction.ConfigureNew,
            decideQuickLaunchGraceResume(null, nowMs = 1_000L),
        )
    }

    @Test
    fun decideQuickLaunchGraceResume_futureDeadline_resume() {
        val existing = QuickLaunchExitResumeSnapshot(deadlineMs = 10_000L, phaseMs = 2_000L, karmaScore = 1)
        val action = decideQuickLaunchGraceResume(existing, nowMs = 1_000L)
        val resume = action as QuickLaunchGraceResumeAction.ResumeExisting
        assertEquals(10_000L, resume.deadlineMs)
        assertEquals(2_000L, resume.phaseMs)
        assertEquals(1, resume.karmaScore)
        assertEquals(4_000L, resume.startedAtMs) // 10000 - 2000*3
    }

    @Test
    fun decideQuickLaunchGraceResume_pastDeadline_enforce() {
        val existing = QuickLaunchExitResumeSnapshot(deadlineMs = 500L, phaseMs = 2_000L, karmaScore = -1)
        val action = decideQuickLaunchGraceResume(existing, nowMs = 1_000L)
        assertTrue(action is QuickLaunchGraceResumeAction.EnforceExpired)
    }

    // --- quickLaunchFrameLevelForNow / semaphorePhaseName ---

    @Test
    fun quickLaunchFrameLevelForNow_table() {
        assertEquals(
            QuickLaunchFrameLevel.GREEN,
            quickLaunchFrameLevelForNow(nowMs = 1_000L, startedAtMs = 0L, phaseMs = 20_000L),
        )
        assertEquals(
            QuickLaunchFrameLevel.GREEN,
            quickLaunchFrameLevelForNow(nowMs = 1_000L, startedAtMs = -1L, phaseMs = 20_000L),
        )

        val startedAt = 1_000L
        val phaseMs = 20_000L
        val cases = listOf(
            0L to QuickLaunchFrameLevel.GREEN,
            5_000L to QuickLaunchFrameLevel.GREEN,
            19_999L to QuickLaunchFrameLevel.GREEN,
            20_000L to QuickLaunchFrameLevel.YELLOW,
            39_999L to QuickLaunchFrameLevel.YELLOW,
            40_000L to QuickLaunchFrameLevel.RED,
            100_000L to QuickLaunchFrameLevel.RED,
        )
        for ((elapsed, expected) in cases) {
            assertEquals(
                "elapsed=$elapsed",
                expected,
                quickLaunchFrameLevelForNow(
                    nowMs = startedAt + elapsed,
                    startedAtMs = startedAt,
                    phaseMs = phaseMs,
                ),
            )
        }
    }

    @Test
    fun semaphorePhaseName_matchesFrameBands() {
        assertEquals("green", semaphorePhaseName(0L, 10_000L))
        assertEquals("yellow", semaphorePhaseName(10_000L, 10_000L))
        assertEquals("red", semaphorePhaseName(20_000L, 10_000L))
    }

    // --- formatQuickLaunchMonitoringStatusText ---

    @Test
    fun formatQuickLaunchMonitoringStatusText_noCandidateUsesDetectedOrDefault() {
        assertEquals(
            "Detected Chrome — allowed",
            formatQuickLaunchMonitoringStatusText(
                candidatePackage = null,
                candidateLabel = null,
                deadlineMs = 0L,
                phaseMs = 20_000L,
                nowMs = 1_000L,
                detectedPackage = "com.chrome",
                detectedStatus = "Detected Chrome — allowed",
            ),
        )
        assertEquals(
            DEFAULT_QUICK_LAUNCH_NOTIFICATION_TEXT,
            formatQuickLaunchMonitoringStatusText(
                candidatePackage = "",
                candidateLabel = null,
                deadlineMs = 0L,
                phaseMs = 20_000L,
                nowMs = 1_000L,
                detectedPackage = "",
                detectedStatus = "ignored",
            ),
        )
    }

    @Test
    fun formatQuickLaunchMonitoringStatusText_phasesAndCountdown() {
        val phaseMs = 20_000L
        val started = 1_000L
        val deadline = started + phaseMs * 3 // 61_000

        assertEquals(
            "Detected Maps. Phase green, opening timer in 60s.",
            formatQuickLaunchMonitoringStatusText(
                candidatePackage = "com.maps",
                candidateLabel = "Maps",
                deadlineMs = deadline,
                phaseMs = phaseMs,
                nowMs = started,
                detectedPackage = "com.maps",
                detectedStatus = "x",
            ),
        )
        assertEquals(
            "Detected Maps. Phase yellow, opening timer in 40s.",
            formatQuickLaunchMonitoringStatusText(
                candidatePackage = "com.maps",
                candidateLabel = "Maps",
                deadlineMs = deadline,
                phaseMs = phaseMs,
                nowMs = started + phaseMs,
                detectedPackage = "com.maps",
                detectedStatus = "x",
            ),
        )
        assertEquals(
            "Detected Maps. Phase red, opening timer in 20s.",
            formatQuickLaunchMonitoringStatusText(
                candidatePackage = "com.maps",
                candidateLabel = "Maps",
                deadlineMs = deadline,
                phaseMs = phaseMs,
                nowMs = started + phaseMs * 2,
                detectedPackage = "com.maps",
                detectedStatus = "x",
            ),
        )
        assertEquals(
            "Detected Maps. Phase red, opening timer now.",
            formatQuickLaunchMonitoringStatusText(
                candidatePackage = "com.maps",
                candidateLabel = "Maps",
                deadlineMs = deadline,
                phaseMs = phaseMs,
                nowMs = deadline,
                detectedPackage = "com.maps",
                detectedStatus = "x",
            ),
        )
    }

    // --- away inference ---

    @Test
    fun mergeLastUserActivityAtMs_takesMaxOfSignals() {
        assertEquals(null, mergeLastUserActivityAtMs(null, null, 0L))
        assertEquals(50L, mergeLastUserActivityAtMs(null, 50L, 0L))
        assertEquals(80L, mergeLastUserActivityAtMs(null, 50L, 80L))
        assertEquals(90L, mergeLastUserActivityAtMs(90L, 50L, 80L))
        assertEquals(100L, mergeLastUserActivityAtMs(40L, 100L, 80L))
    }

    @Test
    fun inferAwayState_unavailableAndThreshold() {
        val unavailable = inferAwayState(null, nowMs = 100_000L)
        assertTrue(unavailable.signalUnavailable)
        assertFalse(unavailable.isUserAway)

        val active = inferAwayState(lastActivityAtMs = 90_000L, nowMs = 100_000L)
        assertFalse(active.isUserAway)
        assertEquals(10_000L, active.inactivityMs)

        val away = inferAwayState(lastActivityAtMs = 30_000L, nowMs = 100_000L)
        assertTrue(away.isUserAway)
        assertEquals(70_000L, away.inactivityMs)
    }

    @Test
    fun decideAwayShieldAction_table() {
        val away = AwayInference(1L, isUserAway = true, inactivityMs = 70_000L, signalUnavailable = false)
        val present = AwayInference(1L, isUserAway = false, inactivityMs = 0L, signalUnavailable = false)
        val missing = AwayInference(null, isUserAway = false, inactivityMs = 0L, signalUnavailable = true)

        assertEquals(
            AwayShieldAction.Show(70_000L),
            decideAwayShieldAction(away, shieldShownForEpisode = false, overlayActive = false),
        )
        assertEquals(
            AwayShieldAction.None,
            decideAwayShieldAction(away, shieldShownForEpisode = true, overlayActive = true),
        )
        assertEquals(
            AwayShieldAction.HideActivityResumed,
            decideAwayShieldAction(present, shieldShownForEpisode = true, overlayActive = true),
        )
        assertEquals(
            AwayShieldAction.ClearEpisodeOnly,
            decideAwayShieldAction(present, shieldShownForEpisode = true, overlayActive = false),
        )
        assertEquals(
            AwayShieldAction.HideUnavailableSignal,
            decideAwayShieldAction(missing, shieldShownForEpisode = true, overlayActive = true),
        )
        assertEquals(
            AwayShieldAction.ClearEpisodeOnly,
            decideAwayShieldAction(missing, shieldShownForEpisode = false, overlayActive = false),
        )
    }

    // --- nudge stage / predatory ---

    @Test
    fun isPredatoryBird_everyTenth() {
        assertFalse(isPredatoryBird(1))
        assertFalse(isPredatoryBird(9))
        assertTrue(isPredatoryBird(10))
        assertTrue(isPredatoryBird(20))
        assertFalse(isPredatoryBird(11))
    }

    @Test
    fun tickNudgeStage_waitingAdvancesWhenDue() {
        assertEquals(NudgeStageTickResult.NoOp, tickNudgeStage(
            stage = NudgeStageLogic.WAITING_AFTER_NOTIFICATION,
            stageElapsedMs = 5_000L,
            initialDelayMs = 10_000L,
            bubbleIntervalMs = 3_000L,
            bubbleCount = 0,
            predatoryPenaltyPending = false,
        ))
        assertEquals(
            NudgeStageTickResult.AdvanceToBubbles(2_000L),
            tickNudgeStage(
                stage = NudgeStageLogic.WAITING_AFTER_NOTIFICATION,
                stageElapsedMs = 12_000L,
                initialDelayMs = 10_000L,
                bubbleIntervalMs = 3_000L,
                bubbleCount = 0,
                predatoryPenaltyPending = false,
            ),
        )
    }

    @Test
    fun tickNudgeStage_bubblesFireAndPredatory() {
        assertEquals(NudgeStageTickResult.NoOp, tickNudgeStage(
            stage = NudgeStageLogic.BUBBLES,
            stageElapsedMs = 2_000L,
            initialDelayMs = 10_000L,
            bubbleIntervalMs = 3_000L,
            bubbleCount = 8,
            predatoryPenaltyPending = false,
        ))

        val ninth = tickNudgeStage(
            stage = NudgeStageLogic.BUBBLES,
            stageElapsedMs = 3_500L,
            initialDelayMs = 10_000L,
            bubbleIntervalMs = 3_000L,
            bubbleCount = 8,
            predatoryPenaltyPending = false,
        ) as NudgeStageTickResult.FireBubble
        assertEquals(9, ninth.newBubbleCount)
        assertFalse(ninth.isPredatory)
        assertFalse(ninth.predatoryPenaltyPendingAfter)
        assertEquals(500L, ninth.stageElapsedMs)

        val tenth = tickNudgeStage(
            stage = NudgeStageLogic.BUBBLES,
            stageElapsedMs = 3_000L,
            initialDelayMs = 10_000L,
            bubbleIntervalMs = 3_000L,
            bubbleCount = 9,
            predatoryPenaltyPending = false,
        ) as NudgeStageTickResult.FireBubble
        assertEquals(10, tenth.newBubbleCount)
        assertTrue(tenth.isPredatory)
        assertTrue(tenth.predatoryPenaltyPendingAfter)

        val eleventhAppliesPenalty = tickNudgeStage(
            stage = NudgeStageLogic.BUBBLES,
            stageElapsedMs = 3_000L,
            initialDelayMs = 10_000L,
            bubbleIntervalMs = 3_000L,
            bubbleCount = 10,
            predatoryPenaltyPending = true,
        ) as NudgeStageTickResult.FireBubble
        assertEquals(11, eleventhAppliesPenalty.newBubbleCount)
        assertTrue(eleventhAppliesPenalty.applyPendingPredatoryPenalty)
        assertFalse(eleventhAppliesPenalty.isPredatory)
        assertFalse(eleventhAppliesPenalty.predatoryPenaltyPendingAfter)
    }

    @Test
    fun shouldStopNudgeLoop_atMaxDuration() {
        assertFalse(shouldStopNudgeLoop(nowMs = 100L, nudgeStartedAtMs = 0L, maxDurationMs = 200L))
        assertTrue(shouldStopNudgeLoop(nowMs = 200L, nudgeStartedAtMs = 0L, maxDurationMs = 200L))
    }

    // --- extension confirmation / deadlines ---

    @Test
    fun parseExtensionConfirmationReply_table() {
        assertEquals(ExtensionConfirmationParse.Confirm, parseExtensionConfirmationReply("yes"))
        assertEquals(ExtensionConfirmationParse.Confirm, parseExtensionConfirmationReply("YES"))
        assertEquals(ExtensionConfirmationParse.Confirm, parseExtensionConfirmationReply("y"))
        assertEquals(ExtensionConfirmationParse.Confirm, parseExtensionConfirmationReply("yes please"))
        assertEquals(
            ExtensionConfirmationParse.Decline,
            parseExtensionConfirmationReply(QUICK_REPLY_DECLINE_EXTENSION),
        )
        assertEquals(
            ExtensionConfirmationParse.Decline,
            parseExtensionConfirmationReply("Fine, I'll close now"),
        )
        assertEquals(ExtensionConfirmationParse.NotADecision, parseExtensionConfirmationReply("maybe later"))
        assertEquals(ExtensionConfirmationParse.NotADecision, parseExtensionConfirmationReply(""))
    }

    @Test
    fun projectExpirationTimeMs_idleNull_andHardCap() {
        assertEquals(
            null,
            projectExpirationTimeMs(nowMs = 1_000L, remainingMs = null, extraMinutes = 5, hardDeadlineAtMs = null),
        )
        assertEquals(
            1_000L + 60_000L + 5 * 60_000L,
            projectExpirationTimeMs(nowMs = 1_000L, remainingMs = 60_000L, extraMinutes = 5, hardDeadlineAtMs = null),
        )
        assertEquals(
            200_000L,
            projectExpirationTimeMs(
                nowMs = 1_000L,
                remainingMs = 60_000L,
                extraMinutes = 10,
                hardDeadlineAtMs = 200_000L,
            ),
        )
        assertEquals(
            1_000L, // Expired remaining 0 + 0 min
            projectExpirationTimeMs(nowMs = 1_000L, remainingMs = 0L, extraMinutes = 0, hardDeadlineAtMs = null),
        )
    }

    @Test
    fun formatExtensionConfirmationMessage_variants() {
        assertEquals(
            "This will now make your timer expire later by 5 minutes. Are you sure?",
            formatExtensionConfirmationMessage(5, null),
        )
        assertEquals(
            "This will now make your timer expire at 3:45 PM. Are you sure?",
            formatExtensionConfirmationMessage(5, "3:45 PM"),
        )
    }

    @Test
    fun hardDeadlineIsCloserThanSession_table() {
        assertFalse(hardDeadlineIsCloserThanSession(1_000L, null, 5_000L))
        assertFalse(hardDeadlineIsCloserThanSession(1_000L, 2_000L, null))
        assertTrue(hardDeadlineIsCloserThanSession(nowMs = 1_000L, hardDeadlineAtMs = 2_000L, sessionDeadlineDistanceMs = 5_000L))
        assertFalse(hardDeadlineIsCloserThanSession(nowMs = 1_000L, hardDeadlineAtMs = 10_000L, sessionDeadlineDistanceMs = 5_000L))
    }

    @Test
    fun sessionDeadlineDistanceMs_prefersRemaining() {
        assertEquals(12L, sessionDeadlineDistanceMs(remainingOrOverrunMs = 12L, idleElapsedMs = 99L))
        assertEquals(0L, sessionDeadlineDistanceMs(remainingOrOverrunMs = -3L, idleElapsedMs = null))
        assertEquals(40L, sessionDeadlineDistanceMs(remainingOrOverrunMs = null, idleElapsedMs = 40L))
        assertEquals(null, sessionDeadlineDistanceMs(remainingOrOverrunMs = null, idleElapsedMs = null))
    }

    // --- mapIntentToCommand ---

    @Test
    fun mapIntentToCommand_nullIntentRestoresOrNoOps() {
        assertEquals(
            TimerServiceCommand.RestoreQuickLaunch,
            mapIntentToCommand(
                action = null,
                quickLaunchSessionActive = true,
                durationMsExtra = -1L,
                durationMinutes = 5,
                packageName = "",
                hardDeadlineRaw = 0L,
                allowedPackages = null,
                probeReason = "probe",
            ),
        )
        assertEquals(
            TimerServiceCommand.NullIntentNoOp,
            mapIntentToCommand(
                action = null,
                quickLaunchSessionActive = false,
                durationMsExtra = -1L,
                durationMinutes = 5,
                packageName = "",
                hardDeadlineRaw = 0L,
                allowedPackages = null,
                probeReason = "probe",
            ),
        )
    }

    @Test
    fun mapIntentToCommand_startUsesExplicitDurationOrMinutes() {
        val fromMs = mapIntentToCommand(
            action = TimerService.ACTION_START,
            quickLaunchSessionActive = false,
            durationMsExtra = 90_000L,
            durationMinutes = 5,
            packageName = "com.app",
            hardDeadlineRaw = 0L,
            allowedPackages = null,
            probeReason = "probe",
        ) as TimerServiceCommand.Start
        assertEquals(90_000L, fromMs.durationMs)
        assertEquals(null, fromMs.hardDeadlineAtMs)

        val fromMin = mapIntentToCommand(
            action = TimerService.ACTION_START,
            quickLaunchSessionActive = false,
            durationMsExtra = -1L,
            durationMinutes = 3,
            packageName = "com.app",
            hardDeadlineRaw = 9_999L,
            allowedPackages = null,
            probeReason = "probe",
        ) as TimerServiceCommand.Start
        assertEquals(180_000L, fromMin.durationMs)
        assertEquals(9_999L, fromMin.hardDeadlineAtMs)
    }

    @Test
    fun mapIntentToCommand_resumeDependsOnSession() {
        assertEquals(
            TimerServiceCommand.ResumeQuickLaunch,
            mapIntentToCommand(
                action = TimerService.ACTION_RESUME_QUICK_LAUNCH_MONITORING,
                quickLaunchSessionActive = true,
                durationMsExtra = -1L,
                durationMinutes = 5,
                packageName = "",
                hardDeadlineRaw = 0L,
                allowedPackages = null,
                probeReason = "probe",
            ),
        )
        assertEquals(
            TimerServiceCommand.IgnoreResumeQuickLaunch,
            mapIntentToCommand(
                action = TimerService.ACTION_RESUME_QUICK_LAUNCH_MONITORING,
                quickLaunchSessionActive = false,
                durationMsExtra = -1L,
                durationMinutes = 5,
                packageName = "",
                hardDeadlineRaw = 0L,
                allowedPackages = null,
                probeReason = "probe",
            ),
        )
    }

    @Test
    fun mapIntentToCommand_simpleActions() {
        assertTrue(
            mapIntentToCommand(
                action = TimerService.ACTION_STOP,
                quickLaunchSessionActive = false,
                durationMsExtra = -1L,
                durationMinutes = 5,
                packageName = "",
                hardDeadlineRaw = 0L,
                allowedPackages = null,
                probeReason = "probe",
            ) is TimerServiceCommand.Stop,
        )
        assertTrue(
            mapIntentToCommand(
                action = "com.mindfulhome.UNKNOWN",
                quickLaunchSessionActive = false,
                durationMsExtra = -1L,
                durationMinutes = 5,
                packageName = "",
                hardDeadlineRaw = 0L,
                allowedPackages = null,
                probeReason = "probe",
            ) is TimerServiceCommand.Unknown,
        )
        val ql = mapIntentToCommand(
            action = TimerService.ACTION_START_QUICK_LAUNCH_SESSION,
            quickLaunchSessionActive = false,
            durationMsExtra = -1L,
            durationMinutes = 5,
            packageName = "com.maps",
            hardDeadlineRaw = 0L,
            allowedPackages = arrayListOf("com.a", "com.b"),
            probeReason = "probe",
        ) as TimerServiceCommand.StartQuickLaunch
        assertEquals("com.maps", ql.packageName)
        assertEquals(setOf("com.a", "com.b"), ql.allowedPackages)
    }

    @Test
    fun quickLaunchIgnoreStatusReason_variants() {
        assertEquals("system_ui", quickLaunchIgnoreStatusReason("system_ui", awaitingHome = false))
        assertEquals(
            "awaiting home after gate leave",
            quickLaunchIgnoreStatusReason("awaiting_home_settle", awaitingHome = true),
        )
        assertEquals(
            "system_ui, awaiting home",
            quickLaunchIgnoreStatusReason("system_ui", awaitingHome = true),
        )
    }

    @Test
    fun classifyStopTimerState_table() {
        assertEquals(StopTimerOutcome.Idle, classifyStopTimerState(
            isIdle = true, isCounting = false, remainingMs = 0, totalMs = 0,
            overrunMs = 0, graceWindowMs = 60_000L,
        ))
        assertEquals(
            StopTimerOutcome.ClosedOnTime(12L, 60L),
            classifyStopTimerState(
                isIdle = false, isCounting = true, remainingMs = 12L, totalMs = 60L,
                overrunMs = 0, graceWindowMs = 60_000L,
            ),
        )
        assertEquals(
            StopTimerOutcome.ClosedInGrace(5_000L),
            classifyStopTimerState(
                isIdle = false, isCounting = false, remainingMs = 0, totalMs = 0,
                overrunMs = 5_000L, graceWindowMs = 60_000L,
            ),
        )
        assertEquals(
            StopTimerOutcome.ClosedAfterOverrun(120_000L),
            classifyStopTimerState(
                isIdle = false, isCounting = false, remainingMs = 0, totalMs = 0,
                overrunMs = 120_000L, graceWindowMs = 60_000L,
            ),
        )
    }

    @Test
    fun countdownHelpers_andInstantGateResume() {
        assertTrue(countdownShouldAbort(0L))
        assertFalse(countdownShouldAbort(1L))
        assertEquals(1_000L, countdownDelayMs(500L, 5_000L))
        assertEquals(2_000L, countdownDelayMs(5_000L, 2_000L))
        assertTrue(shouldFireCountdownExpiry(10L, 10L))
        assertFalse(shouldFireCountdownExpiry(0L, 10L))

        val withResume = instantGateResumeFields(
            QuickLaunchExitResumeSnapshot(1_000L, 20_000L, -1),
            nowMs = 500L,
        )
        assertEquals(1_000L, withResume.deadlineMs)
        assertEquals(20_000L, withResume.phaseMs)
        assertEquals(-1, withResume.karmaScore)
        assertEquals(null, withResume.startedAtMs)

        val without = instantGateResumeFields(null, nowMs = 500L)
        assertEquals(500L, without.deadlineMs)
        assertEquals(500L, without.startedAtMs)
        assertEquals(null, without.phaseMs)
    }

    @Test
    fun conversationNotificationIncludesExtensionActions_flag() {
        assertTrue(conversationNotificationIncludesExtensionActions(true))
        assertFalse(conversationNotificationIncludesExtensionActions(false))
    }
}

@RunWith(Parameterized::class)
class DecideQuickLaunchSwitchParameterizedTest(
    private val name: String,
    private val previousPackage: String,
    private val previousIsUtility: Boolean,
    private val candidate: String?,
    private val resumeDeadline: Long?,
    private val nowMs: Long,
    private val expectedKind: String,
) {
    @Test
    fun decision() {
        val resume = resumeDeadline?.let {
            QuickLaunchExitResumeSnapshot(deadlineMs = it, phaseMs = 20_000L, karmaScore = 0)
        }
        val decision = decideQuickLaunchSwitch(
            packageName = "com.restricted",
            previousPackage = previousPackage,
            selfPackageName = "com.mindfulhome",
            allowedPackages = setOf("com.allowed", "com.mindfulhome"),
            utilityReason = null,
            previousIsUtilityOrSystem = previousIsUtility,
            awaitHomeSettleAfterGateLeave = false,
            currentExitCandidatePackage = candidate,
            resume = resume,
            nowMs = nowMs,
        )
        val kind = when (decision) {
            QuickLaunchSwitchDecision.Allowed -> "allowed"
            is QuickLaunchSwitchDecision.Ignore -> "ignore"
            is QuickLaunchSwitchDecision.InstantGate -> "instant"
            QuickLaunchSwitchDecision.StartGrace -> "grace"
            QuickLaunchSwitchDecision.ContinueMonitor -> "monitor"
        }
        assertEquals(name, expectedKind, kind)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any?>> = listOf(
            arrayOf("from allowed app starts grace", "com.allowed", false, null, null, 1_000L, "grace"),
            arrayOf("same candidate monitors", "com.allowed", false, "com.restricted", null, 1_000L, "monitor"),
            arrayOf("expired resume instant", "com.allowed", false, null, 500L, 1_000L, "instant"),
            arrayOf("active resume starts grace", "com.allowed", false, null, 5_000L, 1_000L, "grace"),
            arrayOf("blank previous instant", "", false, null, null, 1_000L, "instant"),
            arrayOf("self previous instant", "com.mindfulhome", false, null, null, 1_000L, "instant"),
            arrayOf("utility previous instant", "com.sys", true, null, null, 1_000L, "instant"),
            arrayOf("allowlisted utility previous grace", "com.allowed", true, null, null, 1_000L, "grace"),
        )
    }
}

class TimerServiceGateLogicTest {

    @Test
    fun shouldAutoResumeSuspendedSession_matchesOwnerPackage() {
        assertTrue(
            shouldAutoResumeSuspendedSession(
                foregroundPackage = "com.example",
                foregroundOwnerPackage = "com.example",
                savedSessionPackage = "com.example",
                savedRemainingMs = 60_000L,
                timerIsIdleOrExpired = true,
            ),
        )
        assertTrue(
            shouldAutoResumeSuspendedSession(
                foregroundPackage = "shortcut:key",
                foregroundOwnerPackage = "com.example",
                savedSessionPackage = "com.example",
                savedRemainingMs = 60_000L,
                timerIsIdleOrExpired = true,
            ),
        )
        assertFalse(
            shouldAutoResumeSuspendedSession(
                foregroundPackage = "com.other",
                foregroundOwnerPackage = "com.other",
                savedSessionPackage = "com.example",
                savedRemainingMs = 60_000L,
                timerIsIdleOrExpired = true,
            ),
        )
        assertFalse(
            shouldAutoResumeSuspendedSession(
                foregroundPackage = "com.example",
                foregroundOwnerPackage = "com.example",
                savedSessionPackage = "com.example",
                savedRemainingMs = 60_000L,
                timerIsIdleOrExpired = false,
            ),
        )
    }

    @Test
    fun shouldForceShouldYouBeHere_requiresAllGates() {
        assertTrue(
            shouldForceShouldYouBeHere(
                gateActive = true,
                gateDismissed = false,
                timerIsExpired = true,
                packageName = "com.x",
                ownPackageName = "com.me",
                isSystemOrUtility = false,
                isQuickLaunchPackage = false,
                overtimeForceInFlight = false,
            ),
        )
        assertFalse(
            shouldForceShouldYouBeHere(
                gateActive = false,
                gateDismissed = false,
                timerIsExpired = true,
                packageName = "com.x",
                ownPackageName = "com.me",
                isSystemOrUtility = false,
                isQuickLaunchPackage = false,
                overtimeForceInFlight = false,
            ),
        )
        assertFalse(
            shouldForceShouldYouBeHere(
                gateActive = true,
                gateDismissed = true,
                timerIsExpired = true,
                packageName = "com.x",
                ownPackageName = "com.me",
                isSystemOrUtility = false,
                isQuickLaunchPackage = false,
                overtimeForceInFlight = false,
            ),
        )
        assertFalse(
            shouldForceShouldYouBeHere(
                gateActive = true,
                gateDismissed = false,
                timerIsExpired = true,
                packageName = "com.x",
                ownPackageName = "com.me",
                isSystemOrUtility = false,
                isQuickLaunchPackage = true,
                overtimeForceInFlight = false,
            ),
        )
    }

    @Test
    fun shouldIgnoreForegroundWhileAwaitingHome_and_debounce() {
        assertTrue(
            shouldIgnoreForegroundWhileAwaitingHome(
                awaitHomeSettle = true,
                foregroundPackage = "com.x",
                ownPackageName = "com.me",
                isSystemOrUtility = false,
                isQuickLaunchPackage = false,
            ),
        )
        assertFalse(
            shouldIgnoreForegroundWhileAwaitingHome(
                awaitHomeSettle = true,
                foregroundPackage = "com.ql",
                ownPackageName = "com.me",
                isSystemOrUtility = false,
                isQuickLaunchPackage = true,
            ),
        )
        assertTrue(shouldStartTimedQuickLaunchFromTimerState(true))
        assertFalse(shouldStartTimedQuickLaunchFromTimerState(false))
    }
}
