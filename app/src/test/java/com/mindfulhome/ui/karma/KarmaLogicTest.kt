package com.mindfulhome.ui.karma

import com.mindfulhome.data.AppKarma
import com.mindfulhome.model.AppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KarmaLogicTest {

    private fun karma(
        pkg: String,
        score: Int,
        optedOut: Boolean = false,
    ) = AppKarma(
        packageName = pkg,
        karmaScore = score,
        isOptedOut = optedOut,
        totalOpens = 0,
        totalOverruns = 0,
        appNote = null,
        isHidden = false,
    )

    @Test
    fun partitionKarmaApps_groupsByScoreAndOptOut() {
        val all = listOf(
            karma("neg", -3),
            karma("pos", 2),
            karma("zero", 0),
            karma("out", -1, optedOut = true),
            karma("", 5),
        )
        val groups = partitionKarmaApps(all, mapOf("neg" to "NegApp", "pos" to "PosApp"))
        assertEquals(listOf("neg"), groups.negative.map { it.packageName })
        assertEquals(listOf("out"), groups.optedOut.map { it.packageName })
        assertEquals(listOf("pos"), groups.positive.map { it.packageName })
        assertEquals(listOf("zero"), groups.zero.map { it.packageName })
    }

    @Test
    fun karmaScoreColorKey_and_noteHelpers() {
        assertEquals(0, karmaScoreColorKey(true, -10))
        assertEquals(1, karmaScoreColorKey(false, 2))
        assertEquals(2, karmaScoreColorKey(false, -6))
        assertEquals(3, karmaScoreColorKey(false, -2))
        assertEquals(4, karmaScoreColorKey(false, 0))
        assertTrue(noteDraftChanged(" hi ", null))
        assertFalse(noteDraftChanged("note", "note"))
        assertEquals("hi", normalizeNoteDraft(" hi "))
        assertNull(normalizeNoteDraft("   "))
        assertEquals("-10", sanitizeKarmaScoreInput("x-1a0b"))
    }

    @Test
    fun packagesMissingLabels_and_filterApps() {
        val all = listOf(karma("a", 0), karma("b", 0))
        assertEquals(listOf("b"), packagesMissingLabels(all, mapOf("a" to "A")))
        val apps = listOf(
            AppInfo("com.foo", "Foo", null),
            AppInfo("com.bar", "Bar", null),
        )
        assertEquals(1, filterAppsForKarmaPick(apps, "foo").size)
        assertEquals(2, filterAppsForKarmaPick(apps, "").size)
    }

    @Test
    fun filterKarmaGroupsByQuery_matchesLabelOrPackage() {
        val groups = partitionKarmaApps(
            listOf(karma("com.phone", -3), karma("com.chat", -1), karma("com.zero", 0)),
            mapOf("com.phone" to "Phone", "com.chat" to "Chat", "com.zero" to "Zero"),
        )
        val filtered = filterKarmaGroupsByQuery(groups, mapOf(
            "com.phone" to "Phone",
            "com.chat" to "Chat",
            "com.zero" to "Zero",
        ), "phone")
        assertEquals(listOf("com.phone"), filtered.negative.map { it.packageName })
        assertTrue(filtered.zero.isEmpty())
        assertEquals(setOf("negative"), sectionsToExpandForQuery(filtered, true))
    }
}
