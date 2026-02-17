package com.mindfulhome.model

import com.mindfulhome.data.AppRepository

class KarmaManager(private val repository: AppRepository) {

    companion object {
        const val HIDE_THRESHOLD = -10
        const val NUDGE_INTERVAL_MS = 2 * 60 * 1000L // 2 minutes
        const val GRACE_WINDOW_MS = 60 * 1000L // 1 minute grace after expiry
        const val KARMA_PER_NUDGE_IGNORED = -1
        const val KARMA_WEAK_REASON = -1
        const val KARMA_CLOSED_ON_TIME = 1
        const val KARMA_DAILY_RECOVERY = 1
    }

    suspend fun onAppOpened(packageName: String) {
        repository.recordAppOpened(packageName)
    }

    suspend fun onClosedOnTime(packageName: String) {
        repository.recordClosedOnTime(packageName)
    }

    suspend fun onNudgeIgnored(packageName: String) {
        repository.adjustKarma(packageName, KARMA_PER_NUDGE_IGNORED, HIDE_THRESHOLD)
        repository.recordOverrun(packageName)
    }

    suspend fun onWeakReason(packageName: String) {
        repository.adjustKarma(packageName, KARMA_WEAK_REASON, HIDE_THRESHOLD)
    }

    suspend fun onAiExtensionGranted(packageName: String) {
        // AI was convinced -- no karma penalty for the extension period
    }

    suspend fun onClosedInGraceWindow(packageName: String) {
        // Partial recovery: overran but caught themselves quickly
        repository.adjustKarma(packageName, KARMA_CLOSED_ON_TIME, HIDE_THRESHOLD)
    }

    suspend fun isAppHidden(packageName: String): Boolean {
        val karma = repository.getKarma(packageName)
        return karma.isHidden
    }

    suspend fun getKarmaScore(packageName: String): Int {
        return repository.getKarma(packageName).karmaScore
    }

    suspend fun dailyRecovery() {
        repository.dailyKarmaRecovery()
    }
}
