package com.mindfulhome.bootstrap

import android.content.Context
import com.mindfulhome.model.KarmaManager
import com.mindfulhome.settings.SettingsManager
import com.mindfulhome.util.PackageManagerHelper
import com.mindfulhome.util.PreinstalledAppPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-shot: opt out pre-installed (non-media) apps so Phone/etc. are unrestricted
 * without forcing re-onboarding. Skipped once [SettingsManager.isSystemAppsReviewDone].
 */
object SystemAppsBootstrap {
    suspend fun runIfNeeded(context: Context, karmaManager: KarmaManager) {
        if (SettingsManager.isSystemAppsReviewDone(context)) return
        val apps = withContext(Dispatchers.IO) {
            PackageManagerHelper.getInstalledApps(context)
        }
        val candidates = PreinstalledAppPolicy.unrestrictedSystemCandidates(apps)
        for (app in candidates) {
            karmaManager.setOptedOut(app.packageName, true)
        }
        SettingsManager.setSystemAppsReviewDone(context, true)
    }
}
