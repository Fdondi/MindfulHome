package com.mindfulhome.locale

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.mindfulhome.settings.SettingsManager

object LocaleHelper {
    private const val TAG = "LocaleHelper"

    /** Apply the stored app language (or system default) via per-app locales. */
    fun applyStoredLocale(context: Context) {
        apply(SettingsManager.getAppLanguage(context))
    }

    /**
     * @return true when AppCompat will recreate activities for the new locale list.
     * System default is an empty list — already the first-launch state — so this
     * returns false and callers must advance UI themselves.
     */
    fun apply(language: AppLanguage): Boolean {
        val newTags = LocaleHelperLogic.applicationLocaleTags(language)
        val locales = if (newTags.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(newTags)
        }
        val currentTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val recreate = LocaleHelperLogic.shouldRecreateActivity(currentTags, newTags)
        Log.d(TAG, "apply language=${language.tag} current=$currentTags new=$newTags recreate=$recreate")
        if (recreate) {
            AppCompatDelegate.setApplicationLocales(locales)
        }
        return recreate
    }

    /**
     * Persist [language], mark as chosen, and apply. [AppCompatDelegate.setApplicationLocales]
     * recreates activities; callers should not flip UI to localized screens before that completes.
     *
     * @return true when AppCompat will recreate; false if the locale list is already in place.
     */
    fun setLanguage(context: Context, language: AppLanguage): Boolean {
        SettingsManager.setAppLanguage(context, language)
        return apply(language)
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
