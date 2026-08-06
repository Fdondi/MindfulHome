package com.mindfulhome.service

import com.mindfulhome.model.KarmaManager
import kotlin.math.abs
import kotlin.math.max

/** Green / yellow / red Quick Launch overlay frame (pure; mapped to overlay enum in service). */
enum class QuickLaunchFrameLevel {
    GREEN,
    YELLOW,
    RED,
}

/**
 * Decision for [TimerService.maybeForceTimerForQuickLaunchSwitch] once session is active
 * and [packageName] is non-blank.
 */
sealed class QuickLaunchSwitchDecision {
    data object Allowed : QuickLaunchSwitchDecision()

    data class Ignore(
        val reason: String,
    ) : QuickLaunchSwitchDecision()

    /** Grace already used up — start bird nudges; never force home. */
    data object StartBirds : QuickLaunchSwitchDecision()

    data object StartGrace : QuickLaunchSwitchDecision()

    data object ContinueMonitor : QuickLaunchSwitchDecision()
}

data class QuickLaunchExitResumeSnapshot(
    val deadlineMs: Long,
    val phaseMs: Long,
    val karmaScore: Int,
)

data class QuickLaunchGraceTiming(
    val phaseMs: Long,
    val graceMs: Long,
)

sealed class QuickLaunchGraceResumeAction {
    data class ResumeExisting(
        val deadlineMs: Long,
        val phaseMs: Long,
        val karmaScore: Int,
        val startedAtMs: Long,
    ) : QuickLaunchGraceResumeAction()

    data class EnforceExpired(
        val deadlineMs: Long,
        val phaseMs: Long,
        val karmaScore: Int,
        val startedAtMs: Long,
    ) : QuickLaunchGraceResumeAction()

    data object ConfigureNew : QuickLaunchGraceResumeAction()
}

enum class NudgeStageLogic {
    WAITING_AFTER_NOTIFICATION,
    BUBBLES,
}

sealed class NudgeStageTickResult {
    data object NoOp : NudgeStageTickResult()

    data class AdvanceToBubbles(val stageElapsedMs: Long) : NudgeStageTickResult()

    data class FireBubble(
        val stageElapsedMs: Long,
        val newBubbleCount: Int,
        val applyPendingPredatoryPenalty: Boolean,
        val isPredatory: Boolean,
        val predatoryPenaltyPendingAfter: Boolean,
    ) : NudgeStageTickResult()
}

data class AwayInference(
    val lastActivityAtMs: Long?,
    val isUserAway: Boolean,
    val inactivityMs: Long,
    val signalUnavailable: Boolean,
)

sealed class AwayShieldAction {
    data object None : AwayShieldAction()
    data class Show(val inactivityMs: Long) : AwayShieldAction()
    data object HideUnavailableSignal : AwayShieldAction()
    data object HideActivityResumed : AwayShieldAction()
    data object ClearEpisodeOnly : AwayShieldAction()
}

sealed class ExtensionConfirmationParse {
    data object NotADecision : ExtensionConfirmationParse()
    data object Confirm : ExtensionConfirmationParse()
    data object Decline : ExtensionConfirmationParse()
}

internal const val QUICK_LAUNCH_SEMAPHORE_GRACE_PHASES = 3
internal const val USER_AWAY_INACTIVITY_THRESHOLD_MS = 60_000L
internal const val PREDATORY_BIRD_EVERY_N_BIRDS = 10
internal const val DEFAULT_QUICK_LAUNCH_NOTIFICATION_TEXT =
    "Quick Launch active - monitoring app switches"
internal const val QUICK_REPLY_CONFIRM_EXTENSION = "yes"
internal const val QUICK_REPLY_DECLINE_EXTENSION = "oh, it IS late, I'll close"

/**
 * Pure Quick Launch switch decision. Caller must already ensure the session is active and
 * [packageName] is non-blank.
 */
internal fun decideQuickLaunchSwitch(
    packageName: String,
    allowedPackages: Set<String>,
    utilityReason: String?,
    currentExitCandidatePackage: String?,
    resume: QuickLaunchExitResumeSnapshot?,
    nowMs: Long,
): QuickLaunchSwitchDecision {
    when {
        packageName in allowedPackages -> return QuickLaunchSwitchDecision.Allowed
        utilityReason != null -> {
            return QuickLaunchSwitchDecision.Ignore(reason = utilityReason)
        }
        else -> {
            val graceAlreadyUsedUp =
                resume != null && resume.deadlineMs > 0L && nowMs >= resume.deadlineMs
            if (graceAlreadyUsedUp) {
                return QuickLaunchSwitchDecision.StartBirds
            }
            // Launcher, Recents, or switch from another app: always grace first (never block).
            return if (currentExitCandidatePackage != packageName) {
                QuickLaunchSwitchDecision.StartGrace
            } else {
                QuickLaunchSwitchDecision.ContinueMonitor
            }
        }
    }
}

/**
 * Karma → semaphore phase / total grace window for Quick Launch exit countdown.
 */
internal fun computeQuickLaunchGraceTiming(
    karmaScore: Int,
    isOptedOut: Boolean,
    normalPhaseMs: Long,
    positiveMultiplier: Float,
    gracePhases: Int = QUICK_LAUNCH_SEMAPHORE_GRACE_PHASES,
): QuickLaunchGraceTiming {
    val baseGraceMs = normalPhaseMs * gracePhases
    return when {
        isOptedOut -> QuickLaunchGraceTiming(phaseMs = normalPhaseMs, graceMs = baseGraceMs)
        karmaScore > 0 -> {
            val phase = (normalPhaseMs * positiveMultiplier).toLong().coerceAtLeast(5_000L)
            QuickLaunchGraceTiming(phaseMs = phase, graceMs = phase * gracePhases)
        }
        karmaScore < 0 -> {
            val grace = KarmaManager.quickLaunchAllowedStayMs(karmaScore, baseGraceMs)
            val phase = (grace / gracePhases).coerceAtLeast(1_000L)
            QuickLaunchGraceTiming(phaseMs = phase, graceMs = grace)
        }
        else -> QuickLaunchGraceTiming(phaseMs = normalPhaseMs, graceMs = baseGraceMs)
    }
}

internal fun decideQuickLaunchGraceResume(
    existing: QuickLaunchExitResumeSnapshot?,
    nowMs: Long,
    gracePhases: Int = QUICK_LAUNCH_SEMAPHORE_GRACE_PHASES,
): QuickLaunchGraceResumeAction {
    if (existing == null) return QuickLaunchGraceResumeAction.ConfigureNew
    val startedAtMs = existing.deadlineMs - (existing.phaseMs * gracePhases)
    return if (existing.deadlineMs > nowMs) {
        QuickLaunchGraceResumeAction.ResumeExisting(
            deadlineMs = existing.deadlineMs,
            phaseMs = existing.phaseMs,
            karmaScore = existing.karmaScore,
            startedAtMs = startedAtMs,
        )
    } else {
        QuickLaunchGraceResumeAction.EnforceExpired(
            deadlineMs = existing.deadlineMs,
            phaseMs = existing.phaseMs,
            karmaScore = existing.karmaScore,
            startedAtMs = startedAtMs,
        )
    }
}

internal fun quickLaunchFrameLevelForNow(
    nowMs: Long,
    startedAtMs: Long,
    phaseMs: Long,
): QuickLaunchFrameLevel {
    if (startedAtMs <= 0L) return QuickLaunchFrameLevel.GREEN
    val elapsedMs = (nowMs - startedAtMs).coerceAtLeast(0L)
    return when {
        elapsedMs < phaseMs -> QuickLaunchFrameLevel.GREEN
        elapsedMs < phaseMs * 2 -> QuickLaunchFrameLevel.YELLOW
        else -> QuickLaunchFrameLevel.RED
    }
}

internal fun semaphorePhaseName(
    elapsedMs: Long,
    phaseMs: Long,
    green: String = "green",
    yellow: String = "yellow",
    red: String = "red",
): String = when {
    elapsedMs < phaseMs -> green
    elapsedMs < phaseMs * 2 -> yellow
    else -> red
}

internal fun formatQuickLaunchMonitoringStatusText(
    candidatePackage: String?,
    candidateLabel: String?,
    deadlineMs: Long,
    phaseMs: Long,
    nowMs: Long,
    detectedPackage: String,
    detectedStatus: String,
    defaultText: String = DEFAULT_QUICK_LAUNCH_NOTIFICATION_TEXT,
    gracePhases: Int = QUICK_LAUNCH_SEMAPHORE_GRACE_PHASES,
    openingTimerNow: String = "opening timer now",
    openingTimerIn: String = "opening timer in %1\$ds",
    statusFormat: String = "Detected %1\$s. Phase %2\$s, %3\$s.",
    phaseGreen: String = "green",
    phaseYellow: String = "yellow",
    phaseRed: String = "red",
): String {
    if (candidatePackage.isNullOrBlank() || deadlineMs <= 0L) {
        return if (detectedPackage.isNotBlank()) detectedStatus else defaultText
    }
    val label = candidateLabel ?: candidatePackage
    val elapsedMs = if (deadlineMs > 0L) {
        (phaseMs * gracePhases) - (deadlineMs - nowMs).coerceAtLeast(0L)
    } else {
        0L
    }
    val phase = semaphorePhaseName(
        elapsedMs, phaseMs,
        green = phaseGreen, yellow = phaseYellow, red = phaseRed,
    )
    val remainingMs = (deadlineMs - nowMs).coerceAtLeast(0L)
    val remainingSeconds = (remainingMs + 999L) / 1_000L
    val countdownLabel = if (remainingMs <= 0L) {
        openingTimerNow
    } else {
        String.format(openingTimerIn, remainingSeconds)
    }
    return String.format(statusFormat, label, phase, countdownLabel)
}

internal fun mergeLastUserActivityAtMs(
    previous: Long?,
    detectedActivityAtMs: Long?,
    tapActivityAtMs: Long,
): Long? {
    var merged = previous
    if (detectedActivityAtMs != null) {
        merged = if (merged == null) detectedActivityAtMs else max(merged, detectedActivityAtMs)
    }
    if (tapActivityAtMs > 0L) {
        merged = if (merged == null) tapActivityAtMs else max(merged, tapActivityAtMs)
    }
    return merged
}

internal fun inferAwayState(
    lastActivityAtMs: Long?,
    nowMs: Long,
    inactivityThresholdMs: Long = USER_AWAY_INACTIVITY_THRESHOLD_MS,
): AwayInference {
    if (lastActivityAtMs == null) {
        return AwayInference(
            lastActivityAtMs = null,
            isUserAway = false,
            inactivityMs = 0L,
            signalUnavailable = true,
        )
    }
    val inactivityMs = (nowMs - lastActivityAtMs).coerceAtLeast(0L)
    return AwayInference(
        lastActivityAtMs = lastActivityAtMs,
        isUserAway = inactivityMs >= inactivityThresholdMs,
        inactivityMs = inactivityMs,
        signalUnavailable = false,
    )
}

/**
 * Shield UI for one nudge-loop tick after [inferAwayState] / activity merge.
 * When [signalUnavailable], prefer [AwayShieldAction.HideUnavailableSignal] if the overlay is up.
 */
internal fun decideAwayShieldAction(
    inference: AwayInference,
    shieldShownForEpisode: Boolean,
    overlayActive: Boolean,
): AwayShieldAction {
    if (inference.signalUnavailable) {
        return if (overlayActive) {
            AwayShieldAction.HideUnavailableSignal
        } else {
            AwayShieldAction.ClearEpisodeOnly
        }
    }
    return when {
        inference.isUserAway && !shieldShownForEpisode ->
            AwayShieldAction.Show(inactivityMs = inference.inactivityMs)
        inference.isUserAway && shieldShownForEpisode ->
            AwayShieldAction.None
        overlayActive ->
            AwayShieldAction.HideActivityResumed
        else ->
            AwayShieldAction.ClearEpisodeOnly
    }
}

internal fun isPredatoryBird(
    bubbleCount: Int,
    everyN: Int = PREDATORY_BIRD_EVERY_N_BIRDS,
): Boolean = everyN > 0 && bubbleCount % everyN == 0

internal const val NUDGE_CATCH_UP_SPEED_MULTIPLIER = 10

enum class NudgeEscalationPace {
    Normal,
    ConversationGracePaused,
    CatchUp,
}

internal fun resolveNudgeEscalationPace(
    nowMs: Long,
    conversationGraceUntilMs: Long,
    catchUpDebtMs: Long,
): NudgeEscalationPace = when {
    nowMs < conversationGraceUntilMs -> NudgeEscalationPace.ConversationGracePaused
    catchUpDebtMs > 0L -> NudgeEscalationPace.CatchUp
    else -> NudgeEscalationPace.Normal
}

data class NudgeEscalationTickAdvance(
    val stageAdvanceMs: Long,
    val activeAdvanceMs: Long,
    val catchUpDebtMsAfter: Long,
)

/**
 * How much nudge stage/overrun time advances for one loop tick.
 * During conversation grace, debt accumulates (birds suspended). During catch-up, debt burns at
 * [catchUpMultiplier]× until zero, then pace returns to normal.
 */
internal fun computeNudgeEscalationTickAdvance(
    pace: NudgeEscalationPace,
    nudgeTickMs: Long,
    catchUpDebtMs: Long,
    catchUpMultiplier: Int = NUDGE_CATCH_UP_SPEED_MULTIPLIER,
): NudgeEscalationTickAdvance = when (pace) {
    NudgeEscalationPace.ConversationGracePaused -> NudgeEscalationTickAdvance(
        stageAdvanceMs = 0L,
        activeAdvanceMs = 0L,
        catchUpDebtMsAfter = catchUpDebtMs + nudgeTickMs,
    )
    NudgeEscalationPace.CatchUp -> {
        val applied = minOf(nudgeTickMs * catchUpMultiplier.toLong(), catchUpDebtMs)
        NudgeEscalationTickAdvance(
            stageAdvanceMs = applied,
            activeAdvanceMs = applied,
            catchUpDebtMsAfter = catchUpDebtMs - applied,
        )
    }
    NudgeEscalationPace.Normal -> NudgeEscalationTickAdvance(
        stageAdvanceMs = nudgeTickMs,
        activeAdvanceMs = nudgeTickMs,
        catchUpDebtMsAfter = catchUpDebtMs,
    )
}

/** Predatory karma waits until escalation is back at normal speed after catch-up. */
internal fun shouldSuppressPredatoryKarmaForTick(
    pace: NudgeEscalationPace,
    catchUpDebtMsBefore: Long,
    catchUpDebtMsAfter: Long,
): Boolean =
    pace == NudgeEscalationPace.CatchUp ||
        catchUpDebtMsBefore > 0L ||
        catchUpDebtMsAfter > 0L

/**
 * Stage transition after [stageElapsedMs] has already been advanced by one nudge tick.
 */
internal fun tickNudgeStage(
    stage: NudgeStageLogic,
    stageElapsedMs: Long,
    initialDelayMs: Long,
    bubbleIntervalMs: Long,
    bubbleCount: Int,
    predatoryPenaltyPending: Boolean,
    predatoryEveryN: Int = PREDATORY_BIRD_EVERY_N_BIRDS,
): NudgeStageTickResult {
    return when (stage) {
        NudgeStageLogic.WAITING_AFTER_NOTIFICATION -> {
            if (stageElapsedMs >= initialDelayMs) {
                NudgeStageTickResult.AdvanceToBubbles(
                    stageElapsedMs = max(0L, stageElapsedMs - initialDelayMs),
                )
            } else {
                NudgeStageTickResult.NoOp
            }
        }
        NudgeStageLogic.BUBBLES -> {
            if (stageElapsedMs < bubbleIntervalMs) {
                NudgeStageTickResult.NoOp
            } else {
                val nextBubbleIndex = bubbleCount + 1
                val remainingElapsed = max(0L, stageElapsedMs - bubbleIntervalMs)
                val applyPenalty = predatoryPenaltyPending
                val isPredatory = isPredatoryBird(nextBubbleIndex, predatoryEveryN)
                NudgeStageTickResult.FireBubble(
                    stageElapsedMs = remainingElapsed,
                    newBubbleCount = nextBubbleIndex,
                    applyPendingPredatoryPenalty = applyPenalty,
                    isPredatory = isPredatory,
                    predatoryPenaltyPendingAfter = isPredatory,
                )
            }
        }
    }
}

internal fun shouldStopNudgeLoop(
    nowMs: Long,
    nudgeStartedAtMs: Long,
    maxDurationMs: Long,
): Boolean = nowMs - nudgeStartedAtMs >= maxDurationMs

internal fun parseExtensionConfirmationReply(
    payload: String,
    confirmText: String = QUICK_REPLY_CONFIRM_EXTENSION,
    declineText: String = QUICK_REPLY_DECLINE_EXTENSION,
): ExtensionConfirmationParse {
    val normalized = payload.trim().lowercase()
    val confirm = confirmText.lowercase()
    val isConfirm = normalized == confirm ||
        normalized == "y" ||
        normalized.startsWith("$confirm ")
    val isDecline = normalized == declineText.lowercase() ||
        normalized.contains("i'll close")
    return when {
        isConfirm -> ExtensionConfirmationParse.Confirm
        isDecline -> ExtensionConfirmationParse.Decline
        else -> ExtensionConfirmationParse.NotADecision
    }
}

/**
 * Projected wall-clock expiration after adding [extraMinutes]. Returns null when the timer is Idle
 * ([remainingMs] null). Caps at [hardDeadlineAtMs] when set.
 */
internal fun projectExpirationTimeMs(
    nowMs: Long,
    remainingMs: Long?,
    extraMinutes: Int,
    hardDeadlineAtMs: Long?,
): Long? {
    if (remainingMs == null) return null
    val projected = nowMs + remainingMs + extraMinutes.coerceAtLeast(0) * 60_000L
    return if (hardDeadlineAtMs != null && hardDeadlineAtMs > 0L) {
        minOf(projected, hardDeadlineAtMs)
    } else {
        projected
    }
}

internal fun formatExtensionConfirmationMessage(
    minutes: Int,
    formattedExpirationTime: String?,
    byMinutesFormat: String = "This will now make your timer expire later by %1\$d minutes. Are you sure?",
    atTimeFormat: String = "This will now make your timer expire at %1\$s. Are you sure?",
): String {
    return if (formattedExpirationTime == null) {
        String.format(byMinutesFormat, minutes)
    } else {
        String.format(atTimeFormat, formattedExpirationTime)
    }
}

internal fun hardDeadlineIsCloserThanSession(
    nowMs: Long,
    hardDeadlineAtMs: Long?,
    sessionDeadlineDistanceMs: Long?,
): Boolean {
    val hardDeadline = hardDeadlineAtMs ?: return false
    val softDistanceMs = sessionDeadlineDistanceMs ?: return false
    val hardDistanceMs = abs(hardDeadline - nowMs)
    return hardDistanceMs < softDistanceMs
}

internal fun sessionDeadlineDistanceMs(
    remainingOrOverrunMs: Long?,
    idleElapsedMs: Long?,
): Long? {
    remainingOrOverrunMs?.let { return it.coerceAtLeast(0L) }
    idleElapsedMs?.let { return it.coerceAtLeast(0L) }
    return null
}

/** Commands produced by [mapIntentToCommand] for [TimerService.onStartCommand]. */
sealed class TimerServiceCommand {
    data class Start(
        val durationMs: Long,
        val packageName: String,
        val hardDeadlineAtMs: Long?,
    ) : TimerServiceCommand()

    data class StartQuickLaunch(
        val packageName: String,
        val allowedPackages: Set<String>,
    ) : TimerServiceCommand()

    data object ResumeQuickLaunch : TimerServiceCommand()
    data object IgnoreResumeQuickLaunch : TimerServiceCommand()

    data class ProbeQuickLaunch(val reason: String) : TimerServiceCommand()

    data class TrackApp(val packageName: String) : TimerServiceCommand()

    data class ForegroundAppChanged(val packageName: String) : TimerServiceCommand()

    data class Extend(val extraMinutes: Int) : TimerServiceCommand()

    data object Stop : TimerServiceCommand()
    data object EngageExtendChat : TimerServiceCommand()
    data object ClearVisibleNudges : TimerServiceCommand()
    data object HandleReply : TimerServiceCommand()
    data object RestoreQuickLaunch : TimerServiceCommand()
    data object NullIntentNoOp : TimerServiceCommand()
    data object Unknown : TimerServiceCommand()
}

/**
 * Pure intent → command mapping. Side effects stay in the service dispatcher.
 */
internal fun mapIntentToCommand(
    action: String?,
    quickLaunchSessionActive: Boolean,
    durationMsExtra: Long,
    durationMinutes: Int,
    packageName: String,
    hardDeadlineRaw: Long,
    allowedPackages: List<String>?,
    probeReason: String,
): TimerServiceCommand {
    if (action == null) {
        return if (quickLaunchSessionActive) {
            TimerServiceCommand.RestoreQuickLaunch
        } else {
            TimerServiceCommand.NullIntentNoOp
        }
    }
    return when (action) {
        TimerService.ACTION_START -> {
            val hardDeadlineAtMs = hardDeadlineRaw.takeIf { it > 0L }
            val durationMs = if (durationMsExtra > 0L) {
                durationMsExtra
            } else {
                durationMinutes * 60 * 1000L
            }
            TimerServiceCommand.Start(durationMs, packageName, hardDeadlineAtMs)
        }
        TimerService.ACTION_START_QUICK_LAUNCH_SESSION -> TimerServiceCommand.StartQuickLaunch(
            packageName = packageName,
            allowedPackages = allowedPackages?.toSet() ?: emptySet(),
        )
        TimerService.ACTION_RESUME_QUICK_LAUNCH_MONITORING -> {
            if (quickLaunchSessionActive) {
                TimerServiceCommand.ResumeQuickLaunch
            } else {
                TimerServiceCommand.IgnoreResumeQuickLaunch
            }
        }
        TimerService.ACTION_PROBE_QUICK_LAUNCH_FOREGROUND ->
            TimerServiceCommand.ProbeQuickLaunch(probeReason)
        TimerService.ACTION_TRACK_APP -> TimerServiceCommand.TrackApp(packageName)
        TimerService.ACTION_FOREGROUND_APP_CHANGED ->
            TimerServiceCommand.ForegroundAppChanged(packageName)
        TimerService.ACTION_EXTEND -> TimerServiceCommand.Extend(durationMinutes)
        TimerService.ACTION_STOP -> TimerServiceCommand.Stop
        TimerService.ACTION_ENGAGE_EXTEND_CHAT -> TimerServiceCommand.EngageExtendChat
        TimerService.ACTION_CLEAR_VISIBLE_NUDGES -> TimerServiceCommand.ClearVisibleNudges
        TimerService.ACTION_HANDLE_REPLY -> TimerServiceCommand.HandleReply
        else -> TimerServiceCommand.Unknown
    }
}

internal fun quickLaunchIgnoreStatusReason(reason: String): String = reason

/** Whether conversation notification should offer extension confirm/decline actions. */
internal fun conversationNotificationIncludesExtensionActions(
    hasPendingExtensionDecision: Boolean,
): Boolean = hasPendingExtensionDecision

/**
 * Classifies timer state at stop for karma / logging (side effects stay in service).
 */
sealed class StopTimerOutcome {
    data class ClosedOnTime(val remainingMs: Long, val totalMs: Long) : StopTimerOutcome()
    data class ClosedInGrace(val overrunMs: Long) : StopTimerOutcome()
    data class ClosedAfterOverrun(val overrunMs: Long) : StopTimerOutcome()
    data object Idle : StopTimerOutcome()
}

internal fun classifyStopTimerState(
    isIdle: Boolean,
    isCounting: Boolean,
    remainingMs: Long,
    totalMs: Long,
    overrunMs: Long,
    graceWindowMs: Long,
): StopTimerOutcome = when {
    isIdle -> StopTimerOutcome.Idle
    isCounting -> StopTimerOutcome.ClosedOnTime(remainingMs = remainingMs, totalMs = totalMs)
    overrunMs <= graceWindowMs -> StopTimerOutcome.ClosedInGrace(overrunMs)
    else -> StopTimerOutcome.ClosedAfterOverrun(overrunMs)
}

/** Whether the countdown coroutine should stop because end time was cleared. */
internal fun countdownShouldAbort(endAtMs: Long): Boolean = endAtMs <= 0L

/** Remaining delay for one countdown tick (never zero when still running). */
internal fun countdownDelayMs(tickMs: Long, untilEndMs: Long): Long =
    minOf(tickMs.coerceAtLeast(1_000L), untilEndMs).coerceAtLeast(1L)

internal fun shouldFireCountdownExpiry(endAtMs: Long, nowMs: Long): Boolean =
    endAtMs > 0L && nowMs >= endAtMs

/**
 * True when the user returned to the app whose timer was suspended for Quick Launch.
 * Resuming is invisible — no confrontation UI or home redirect.
 */
internal fun shouldAutoResumeSuspendedSession(
    foregroundPackage: String,
    foregroundOwnerPackage: String,
    savedSessionPackage: String?,
    savedRemainingMs: Long,
    timerIsIdleOrExpired: Boolean,
): Boolean {
    if (!timerIsIdleOrExpired) return false
    if (savedSessionPackage.isNullOrBlank() || savedRemainingMs <= 0L) return false
    return foregroundPackage == savedSessionPackage ||
        foregroundOwnerPackage == savedSessionPackage
}

internal fun shouldStartTimedQuickLaunchFromTimerState(isIdle: Boolean): Boolean = isIdle

internal fun stopTimerOutcomeFromStateFlags(
    isIdle: Boolean,
    isCounting: Boolean,
    remainingMs: Long,
    totalMs: Long,
    overrunMs: Long,
    graceWindowMs: Long,
): StopTimerOutcome = classifyStopTimerState(
    isIdle = isIdle,
    isCounting = isCounting,
    remainingMs = remainingMs,
    totalMs = totalMs,
    overrunMs = overrunMs,
    graceWindowMs = graceWindowMs,
)

