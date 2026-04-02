package com.mindfulhome.model

import android.content.Context
import com.mindfulhome.data.AppRepository
import com.mindfulhome.settings.SettingsManager
import java.util.concurrent.TimeUnit

class KarmaManager(private val context: Context, private val repository: AppRepository) {

    companion object {
        const val NUDGE_INTERVAL_MS = 2 * 60 * 1000L // 2 minutes
        const val GRACE_WINDOW_MS = 60 * 1000L // 1 minute grace after expiry
        const val KARMA_PER_NUDGE_IGNORED = -1
        const val KARMA_WEAK_REASON = -1
        const val KARMA_CLOSED_ON_TIME = 1
        const val KARMA_DAILY_RECOVERY = 1
        const val KARMA_QUICK_LAUNCH_EXIT_AFTER_RED = -1
    }

    private fun hideThreshold(): Int = -SettingsManager.getHideThreshold(context)

    suspend fun onAppOpened(packageName: String) {
        repository.recordAppOpened(packageName)
    }

    suspend fun onClosedOnTime(packageName: String) {
        repository.recordClosedOnTime(packageName, hideThreshold())
    }

    suspend fun onNudgeIgnored(packageName: String) {
        if (repository.getKarma(packageName).isOptedOut) return
        repository.adjustKarma(packageName, KARMA_PER_NUDGE_IGNORED, hideThreshold())
        repository.recordOverrun(packageName)
    }

    suspend fun onWeakReason(packageName: String) {
        if (repository.getKarma(packageName).isOptedOut) return
        repository.adjustKarma(packageName, KARMA_WEAK_REASON, hideThreshold())
    }

    suspend fun onAiExtensionGranted(packageName: String) {
        // AI was convinced -- no karma penalty for the extension period
    }

    suspend fun onClosedInGraceWindow(packageName: String) {
        // Partial recovery: overran but caught themselves quickly
        repository.adjustKarma(packageName, KARMA_CLOSED_ON_TIME, hideThreshold())
    }

    /** Quick Launch: stayed on a non-allowed app until forced return after the red semaphore phase. */
    suspend fun onQuickLaunchExitAfterRed(packageName: String) {
        if (packageName.isBlank()) return
        if (repository.getKarma(packageName).isOptedOut) return
        repository.adjustKarma(packageName, KARMA_QUICK_LAUNCH_EXIT_AFTER_RED, hideThreshold())
    }

    suspend fun isAppHidden(packageName: String): Boolean {
        val karma = repository.getKarma(packageName)
        return karma.isHidden
    }

    suspend fun getKarmaScore(packageName: String): Int {
        return repository.getKarma(packageName).karmaScore
    }

    suspend fun forgiveApp(packageName: String) {
        repository.forgiveApp(packageName)
    }

    suspend fun setOptedOut(packageName: String, optedOut: Boolean) {
        repository.setOptedOut(packageName, optedOut)
    }

    suspend fun dailyRecovery() {
        repository.dailyKarmaRecovery(hideThreshold())
    }

    suspend fun runDailyRecoveryIfDue(nowMs: Long = System.currentTimeMillis()) {
        val todayEpochDay = TimeUnit.MILLISECONDS.toDays(nowMs)
        val lastRecoveryEpochDay = SettingsManager.getLastKarmaRecoveryEpochDay(context)

        if (lastRecoveryEpochDay < 0L) {
            SettingsManager.setLastKarmaRecoveryEpochDay(context, todayEpochDay)
            return
        }
        if (todayEpochDay <= lastRecoveryEpochDay) return

        val missedDays = (todayEpochDay - lastRecoveryEpochDay).coerceAtMost(30L)
        repeat(missedDays.toInt()) {
            dailyRecovery()
        }
        SettingsManager.setLastKarmaRecoveryEpochDay(context, todayEpochDay)
    }
}
