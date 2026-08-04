package com.mindfulhome.locale

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.mindfulhome.settings.SettingsManager

object LocaleHelper {

    /** Apply the stored app language (or system default) via per-app locales. */
    fun applyStoredLocale(context: Context) {
        apply(SettingsManager.getAppLanguage(context))
    }

    fun apply(language: AppLanguage) {
        val locales = if (language == AppLanguage.SYSTEM) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(language.tag)
        }
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
     * Context whose [Context.getString] / resources match the effective app language.
     *
     * [AppCompatDelegate.setApplicationLocales] updates Activities, but Services and the
     * Application context often keep the system locale — so notification and offline-AI
     * fallbacks would stay English without this wrap.
     *
     * When the preference is [AppLanguage.SYSTEM], wraps to the resolved device match.
     */
    fun wrap(base: Context): Context {
        val language = SettingsManager.getAppLanguage(base).resolve()
        val locale = language.toLocale()
        val existing = base.resources.configuration.locales
        if (existing.size() > 0 && existing[0] == locale) {
            return base
        }
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /**
     * For users who already finished onboarding before language prefs existed:
     * follow the system language and mark chosen so they are not forced through the
     * welcome language step.
     */
    fun migrateExistingUsersIfNeeded(context: Context, onboardingDone: Boolean) {
        if (SettingsManager.hasChosenAppLanguage(context)) return
        if (!onboardingDone) return
        SettingsManager.setAppLanguage(context, AppLanguage.SYSTEM)
        apply(AppLanguage.SYSTEM)
    }
}
