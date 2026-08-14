package com.mindfulhome.locale

/** Pure locale-apply decisions; [LocaleHelper] talks to AppCompat. */
object LocaleHelperLogic {
    /** Tags passed to AppCompat; empty list means follow the device locale. */
    fun applicationLocaleTags(language: AppLanguage): String =
        if (language == AppLanguage.SYSTEM) "" else language.tag

    /** AppCompat only recreates when the per-app locale list actually changes. */
    fun shouldRecreateActivity(currentTags: String, newTags: String): Boolean =
        currentTags != newTags
}
