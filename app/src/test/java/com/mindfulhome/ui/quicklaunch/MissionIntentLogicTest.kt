package com.mindfulhome.ui.quicklaunch

import com.mindfulhome.data.QuickLaunchFolderApp
import com.mindfulhome.data.QuickLaunchSlot
import com.mindfulhome.model.AppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionIntentLogicTest {

    private fun app(pkg: String) = AppInfo(pkg, pkg, null)

    @Test
    fun formatIntentMinutes_variants() {
        assertEquals("45m", formatIntentMinutes(45))
        assertEquals("2h", formatIntentMinutes(120))
        assertEquals("1h 5m", formatIntentMinutes(65))
    }

    @Test
    fun hasEmptyNamedIntentFolder_detection() {
        assertTrue(
            hasEmptyNamedIntentFolder(
                listOf(QuickLaunchSlot.Folder("Learn", emptyList())),
            ),
        )
        assertFalse(
            hasEmptyNamedIntentFolder(
                listOf(QuickLaunchSlot.Folder(null, emptyList())),
            ),
        )
        assertFalse(
            hasEmptyNamedIntentFolder(
                listOf(QuickLaunchSlot.Folder("Learn", listOf(QuickLaunchFolderApp.unlimited("a")))),
            ),
        )
    }

    @Test
    fun mapIntentSlotsToUi_skipsSinglesAndUsesUnnamed() {
        val installed = mapOf("a" to app("a"), "b" to app("b"))
        val ui = mapIntentSlotsToUi(
            listOf(
                QuickLaunchSlot.Single("a"),
                QuickLaunchSlot.Folder(null, listOf(QuickLaunchFolderApp.unlimited("b"))),
            ),
            installed,
        ) { emptyList() }
        assertEquals(1, ui.size)
        assertEquals("Unnamed", ui[0].folderName)
        assertEquals(listOf("b"), ui[0].apps.map { it.packageName })
    }

    @Test
    fun mapIntentSlotsToUi_showsFolderAppsFromPerPackageResolve() {
        // Full catalog empty / loading: only slot members resolved.
        val resolved = mapOf("a" to app("a"), "b" to app("b"))
        val ui = mapIntentSlotsToUi(
            listOf(
                QuickLaunchSlot.Folder(
                    "Focus",
                    listOf(
                        QuickLaunchFolderApp.unlimited("a"),
                        QuickLaunchFolderApp.unlimited("b"),
                    ),
                ),
            ),
            resolved,
        ) { emptyList() }
        assertEquals(1, ui.size)
        assertEquals("Focus", ui[0].folderName)
        assertEquals(listOf("a", "b"), ui[0].apps.map { it.packageName })
    }

    @Test
    fun reconcileOpenIntentFolder_closesOnMissingOrSingle() {
        val open = QuickLaunchFolderOpen(0, listOf(app("a"), app("b")), "F", null)
        assertNull(reconcileOpenIntentFolder(open, emptyList(), emptyMap()) { emptyList() })
        assertNull(
            reconcileOpenIntentFolder(
                open,
                listOf(QuickLaunchSlot.Single("a")),
                mapOf("a" to app("a")),
            ) { emptyList() },
        )
        val folder = QuickLaunchSlot.Folder(
            "F",
            listOf(QuickLaunchFolderApp.unlimited("a"), QuickLaunchFolderApp.timed("b", 3)),
        )
        val next = reconcileOpenIntentFolder(
            open,
            listOf(folder),
            mapOf("a" to app("a"), "b" to app("b")),
        ) { emptyList() }
        assertEquals(listOf("a", "b"), next!!.apps.map { it.packageName })
        assertEquals(3, next.appLimitsByPackage["b"])
    }

    @Test
    fun resumeTile_and_helpers() {
        assertNull(buildResumeAuxTile(null, 10, {}))
        assertNull(buildResumeAuxTile("Session", 0, {}))
        val tile = buildResumeAuxTile("Session", 30, {})!!
        assertEquals("Resume", tile.label)
        assertTrue(tile.subtitle!!.contains("30m"))
        assertEquals(
            listOf("gone"),
            missingPackagesInSlots(
                listOf(QuickLaunchSlot.Single("gone"), QuickLaunchSlot.Single("here")),
                setOf("here"),
            ),
        )
        assertEquals(emptyList<String>(), missingPackagesInSlots(listOf(QuickLaunchSlot.Single("x")), emptySet()))
        assertEquals("Learn", trimmedNonEmptyName(" Learn "))
        assertNull(trimmedNonEmptyName("  "))
        assertEquals("Unnamed intent", folderTitleForIntent(QuickLaunchFolderOpen(0, emptyList(), null, null)))
        assertEquals(
            "Focus",
            folderTitleForIntent(QuickLaunchFolderOpen(0, emptyList(), "Focus", null)),
        )
    }
}
