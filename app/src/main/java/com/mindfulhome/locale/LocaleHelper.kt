package com.mindfulhome.locale

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.mindfulhome.settings.SettingsManager

object LocaleHelper {

    /** Apply the stored app language (or English) via per-app locales. */
    fun applyStoredLocale(context: Context) {
        apply(SettingsManager.getAppLanguage(context))
    }

    fun apply(language: AppLanguage) {
        val locales = LocaleListCompat.forLanguageTags(language.tag)
        val currentTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (currentTags != locales.toLanguageTags()) {
            // Recreates activities so Compose stringResource picks up the new configuration.
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }

    /**
     * Persist [language], mark as chosen, and apply. [AppCompatDelegate.setApplicationLocales]
     * recreates activities; callers should not flip UI to localized screens before that completes.
     */
    fun setLanguage(context: Context, language: AppLanguage) {
        SettingsManager.setAppLanguage(context, language)
        apply(language)
    }

    /**
     * For users who already finished onboarding before language prefs existed:
     * pick a supported system match (else English) and mark chosen so they are not
     * forced through the welcome language step.
     */
    fun migrateExistingUsersIfNeeded(context: Context, onboardingDone: Boolean) {
        if (SettingsManager.hasChosenAppLanguage(context)) return
        if (!onboardingDone) return
        val language = AppLanguage.matchSystem() ?: AppLanguage.ENGLISH
        SettingsManager.setAppLanguage(context, language)
        apply(language)
    }
}
