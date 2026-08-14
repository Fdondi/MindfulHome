package com.mindfulhome.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocaleHelperLogicTest {

    @Test
    fun systemDefaultUsesEmptyLocaleTags() {
        assertEquals("", LocaleHelperLogic.applicationLocaleTags(AppLanguage.SYSTEM))
        assertEquals("en", LocaleHelperLogic.applicationLocaleTags(AppLanguage.ENGLISH))
        assertEquals("zh-CN", LocaleHelperLogic.applicationLocaleTags(AppLanguage.CHINESE_SIMPLIFIED))
    }

    @Test
    fun firstLaunchSystemDefaultDoesNotRecreate() {
        val current = ""
        val chosen = LocaleHelperLogic.applicationLocaleTags(AppLanguage.SYSTEM)
        assertFalse(LocaleHelperLogic.shouldRecreateActivity(current, chosen))
    }

    @Test
    fun pickingAFixedLanguageRecreatesFromEmptyList() {
        val current = ""
        val chosen = LocaleHelperLogic.applicationLocaleTags(AppLanguage.ITALIAN)
        assertTrue(LocaleHelperLogic.shouldRecreateActivity(current, chosen))
    }

    @Test
    fun switchingBackToSystemDefaultRecreates() {
        val current = "de"
        val chosen = LocaleHelperLogic.applicationLocaleTags(AppLanguage.SYSTEM)
        assertTrue(LocaleHelperLogic.shouldRecreateActivity(current, chosen))
    }
}
