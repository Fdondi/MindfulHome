from pathlib import Path

p = Path(r"c:\Users\franc\Documents\GitHub\MindfulHome\app\src\main\java\com\mindfulhome\service\TimerService.kt")
text = p.read_text(encoding="utf-8")
start = text.find("    private fun dispatchStartCommands")
end = text.find("    private fun handleProbeQuickLaunch")
assert start != -1 and end != -1, (start, end)
new = r'''    private fun dispatchStartCommands(command: TimerServiceCommand): Boolean = when (command) {
        is TimerServiceCommand.Start -> {
            handleStartCommand(command); true
        }
        is TimerServiceCommand.StartQuickLaunch -> {
            handleStartQuickLaunchCommand(command); true
        }
        is TimerServiceCommand.Extend, TimerServiceCommand.Stop -> {
            handleExtendOrStop(command); true
        }
        else -> false
    }

    private fun handleStartCommand(command: TimerServiceCommand.Start) {
        logSessionEvent(
            "ACTION_START requested: durationMs=${command.durationMs} " +
                "package=${command.packageName.ifBlank { "<none>" }} " +
                "hardDeadlineAtMs=${command.hardDeadlineAtMs ?: 0L}",
        )
        startTimer(command.durationMs, command.packageName, command.hardDeadlineAtMs)
    }

    private fun handleStartQuickLaunchCommand(command: TimerServiceCommand.StartQuickLaunch) {
        logSessionEvent(
            "ACTION_START_QUICK_LAUNCH_SESSION requested: " +
                "initial=${command.packageName.ifBlank { "<none>" }} " +
                "allowed=${command.allowedPackages.size}",
        )
        startQuickLaunchSession(command.packageName, command.allowedPackages)
    }

    private fun handleExtendOrStop(command: TimerServiceCommand) {
        when (command) {
            is TimerServiceCommand.Extend -> {
                logSessionEvent("ACTION_EXTEND requested: +${command.extraMinutes} min")
                if (!extendTimer(command.extraMinutes)) {
                    logWithSession("Extension blocked due to hard deadline proximity")
                }
            }
            TimerServiceCommand.Stop -> {
                logSessionEvent("ACTION_STOP requested")
                stopTimer()
            }
            else -> Unit
        }
    }

    private fun dispatchQuickLaunchCommands(command: TimerServiceCommand): Boolean = when (command) {
        TimerServiceCommand.ResumeQuickLaunch,
        TimerServiceCommand.IgnoreResumeQuickLaunch,
        TimerServiceCommand.RestoreQuickLaunch,
        -> {
            handleQlSessionCommand(command); true
        }
        is TimerServiceCommand.ProbeQuickLaunch -> {
            handleProbeQuickLaunch(command.reason); true
        }
        is TimerServiceCommand.TrackApp -> {
            handleTrackAppCommand(command.packageName); true
        }
        is TimerServiceCommand.ForegroundAppChanged -> {
            handleForegroundAppChanged(command.packageName); true
        }
        else -> false
    }

    private fun handleQlSessionCommand(command: TimerServiceCommand) {
        when (command) {
            TimerServiceCommand.ResumeQuickLaunch -> {
                logSessionEvent("ACTION_RESUME_QUICK_LAUNCH_MONITORING requested")
                restoreQuickLaunchMonitoring(reason = "unlock")
            }
            TimerServiceCommand.IgnoreResumeQuickLaunch ->
                logSessionEvent("Ignoring quick-launch resume: session not active")
            TimerServiceCommand.RestoreQuickLaunch -> {
                Log.w(TAG, "Null intent restart - restoring quick launch monitoring")
                logSessionEvent("Service restarted with null intent — restoring quick launch monitor")
                restoreQuickLaunchMonitoring(reason = "null-intent restart")
            }
            else -> Unit
        }
    }

    private fun dispatchSessionCommands(command: TimerServiceCommand, intent: Intent?): Boolean =
        when (command) {
            TimerServiceCommand.DismissShouldYouBeHere,
            TimerServiceCommand.EngageExtendChat,
            TimerServiceCommand.ClearVisibleNudges,
            TimerServiceCommand.HandleReply,
            TimerServiceCommand.NullIntentNoOp,
            TimerServiceCommand.Unknown,
            -> {
                handleSessionSideCommand(command, intent); true
            }
            else -> false
        }

    private fun handleSessionSideCommand(command: TimerServiceCommand, intent: Intent?) {
        when (command) {
            TimerServiceCommand.DismissShouldYouBeHere -> {
                logSessionEvent("ACTION_DISMISS_SHOULD_YOU_BE_HERE requested")
                dismissShouldYouBeHereAndStop()
            }
            TimerServiceCommand.EngageExtendChat -> {
                logSessionEvent("ACTION_ENGAGE_EXTEND_CHAT requested")
                engageExtendChat()
            }
            TimerServiceCommand.ClearVisibleNudges -> {
                val cleared = overlayManager.dismissAllNudgesIfPresent()
                Log.d(
                    TAG,
                    if (cleared) "ACTION_CLEAR_VISIBLE_NUDGES: removed visible nudges"
                    else "ACTION_CLEAR_VISIBLE_NUDGES: no-op (nothing visible)",
                )
            }
            TimerServiceCommand.HandleReply -> {
                if (intent != null) handleNudgeReply(intent)
            }
            TimerServiceCommand.NullIntentNoOp, TimerServiceCommand.Unknown -> Unit
            else -> Unit
        }
    }


'''
p.write_text(text[:start] + new + text[end:], encoding="utf-8")
print("ok", start, end)
