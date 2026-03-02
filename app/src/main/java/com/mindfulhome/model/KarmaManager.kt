package com.mindfulhome.model

import android.content.Context
import com.mindfulhome.data.AppRepository
import com.mindfulhome.settings.SettingsManager

class KarmaManager(private val context: Context, private val repository: AppRepository) {

    companion object {
        const val NUDGE_INTERVAL_MS = 2 * 60 * 1000L // 2 minutes
        const val GRACE_WINDOW_MS = 60 * 1000L // 1 minute grace after expiry
        const val KARMA_PER_NUDGE_IGNORED = -1
        const val KARMA_WEAK_REASON = -1
        const val KARMA_CLOSED_ON_TIME = 1
        const val KARMA_DAILY_RECOVERY = 1
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
}
