package com.mindfulhome.locale

import java.util.Locale

/**
 * In-app languages. [nativeName] is always shown in that language so users can pick
 * before translations load.
 */
enum class AppLanguage(
    /** BCP-47 tag used with AppCompat / Android locale APIs. */
    val tag: String,
    /** Name of the language written in that language. */
    val nativeName: String,
    /** English name — used in AI “write in …” instructions. */
    val englishName: String,
) {
    ENGLISH("en", "English", "English"),
    GERMAN("de", "Deutsch", "German"),
    FRENCH("fr", "Français", "French"),
    ITALIAN("it", "Italiano", "Italian"),
    SPANISH("es", "Español", "Spanish"),
    CHINESE_SIMPLIFIED("zh-CN", "中文", "Simplified Chinese"),
    JAPANESE("ja", "日本語", "Japanese"),
    ;

    fun toLocale(): Locale = Locale.forLanguageTag(tag)

    companion object {
        fun fromTag(tag: String?): AppLanguage? {
            if (tag.isNullOrBlank()) return null
            val normalized = tag.replace('_', '-').lowercase(Locale.ROOT)
            return entries.firstOrNull {
                it.tag.lowercase(Locale.ROOT) == normalized ||
                    it.tag.lowercase(Locale.ROOT).substringBefore('-') ==
                    normalized.substringBefore('-')
            }
        }

        /** Best match for the device locale among supported languages, or null. */
        fun matchSystem(locale: Locale = Locale.getDefault()): AppLanguage? {
            val tag = locale.toLanguageTag()
            fromTag(tag)?.let { return it }
            fromTag(locale.language)?.let { return it }
            // zh-Hans / zh_CN variants
            if (locale.language.equals("zh", ignoreCase = true)) {
                return CHINESE_SIMPLIFIED
            }
            return null
        }
    }
}
