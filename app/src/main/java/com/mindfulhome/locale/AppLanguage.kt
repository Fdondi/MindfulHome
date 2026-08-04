package com.mindfulhome.locale

import java.util.Locale

/**
 * In-app languages. [nativeName] is always shown in that language so users can pick
 * before translations load. [SYSTEM] follows the device locale (see [resolve]).
 */
enum class AppLanguage(
    /** BCP-47 tag used with AppCompat / Android locale APIs; `"system"` for follow-device. */
    val tag: String,
    /** Name of the language written in that language (unused for [SYSTEM] in the picker). */
    val nativeName: String,
    /** English name — used in AI “write in …” instructions. */
    val englishName: String,
) {
    SYSTEM("system", "System default", "System default"),
    ENGLISH("en", "English", "English"),
    GERMAN("de", "Deutsch", "German"),
    FRENCH("fr", "Français", "French"),
    ITALIAN("it", "Italiano", "Italian"),
    SPANISH("es", "Español", "Spanish"),
    CHINESE_SIMPLIFIED("zh-CN", "中文", "Simplified Chinese"),
    JAPANESE("ja", "日本語", "Japanese"),
    ;

    fun toLocale(): Locale = when (this) {
        SYSTEM -> resolve().toLocale()
        else -> Locale.forLanguageTag(tag)
    }

    /** Concrete language for resources / AI. [SYSTEM] → best device match, else English. */
    fun resolve(locale: Locale = Locale.getDefault()): AppLanguage =
        if (this != SYSTEM) this else matchSystem(locale) ?: ENGLISH

    companion object {
        /** Fixed languages shown under System default in the picker. */
        val fixedEntries: List<AppLanguage> = entries.filter { it != SYSTEM }

        fun fromTag(tag: String?): AppLanguage? {
            if (tag.isNullOrBlank()) return null
            val normalized = tag.replace('_', '-').lowercase(Locale.ROOT)
            if (normalized == SYSTEM.tag) return SYSTEM
            return fixedEntries.firstOrNull {
                it.tag.lowercase(Locale.ROOT) == normalized ||
                    it.tag.lowercase(Locale.ROOT).substringBefore('-') ==
                    normalized.substringBefore('-')
            }
        }

        /** Best match for the device locale among supported languages, or null. */
        fun matchSystem(locale: Locale = Locale.getDefault()): AppLanguage? {
            val tag = locale.toLanguageTag()
            fromTag(tag)?.takeIf { it != SYSTEM }?.let { return it }
            fromTag(locale.language)?.takeIf { it != SYSTEM }?.let { return it }
            // zh-Hans / zh_CN variants
            if (locale.language.equals("zh", ignoreCase = true)) {
                return CHINESE_SIMPLIFIED
            }
            return null
        }
    }
}
