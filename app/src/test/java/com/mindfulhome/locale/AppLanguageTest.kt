package com.mindfulhome.locale

import com.mindfulhome.ai.PromptTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class AppLanguageTest {
    @Test
    fun fromTag_matchesSupportedLanguages() {
        assertEquals(AppLanguage.GERMAN, AppLanguage.fromTag("de"))
        assertEquals(AppLanguage.CHINESE_SIMPLIFIED, AppLanguage.fromTag("zh-CN"))
        assertEquals(AppLanguage.JAPANESE, AppLanguage.fromTag("ja-JP"))
    }

    @Test
    fun matchSystem_chineseVariants() {
        assertEquals(
            AppLanguage.CHINESE_SIMPLIFIED,
            AppLanguage.matchSystem(Locale.forLanguageTag("zh-Hans-CN")),
        )
    }

    @Test
    fun replyLanguageInstruction_mentionsEnglishNameAndTag() {
        val instruction = PromptTemplates.replyLanguageInstruction(AppLanguage.FRENCH)
        assertTrue(instruction.contains("French"))
        assertTrue(instruction.contains("fr"))
    }
}
