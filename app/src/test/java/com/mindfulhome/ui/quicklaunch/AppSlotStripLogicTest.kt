package com.mindfulhome.ui.quicklaunch

import com.mindfulhome.data.QuickLaunchFolderApp
import com.mindfulhome.data.QuickLaunchSlot
import com.mindfulhome.model.AppInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSlotStripLogicTest {

    private fun app(pkg: String) = AppInfo(pkg, pkg, null)

    @Test
    fun stripCopy_quickLaunchAndFavoritesDiffer() {
        val ql = stripCopy(AppSlotStripKind.QuickLaunch)
        val fav = stripCopy(AppSlotStripKind.Favorites)
        assertEquals("QuickLaunch", ql.stripTitle)
        assertEquals("Favorites", fav.stripTitle)
        assertTrue(ql.addDialogTitle.contains("QuickLaunch"))
        assertTrue(fav.addDialogTitle.contains("Favorites"))
        assertTrue(ql.addToFolderTitle.contains("folder"))
        assertTrue(fav.folderHintRemove.contains("Favorites"))
    }

    @Test
    fun mapSlotsToUi_skipsMissingAndCollapsesFolderNameForSingleApp() {
        val installed = mapOf(
            "a" to app("a"),
            "b" to app("b"),
        )
        val raw = listOf(
            QuickLaunchSlot.Single("a"),
            QuickLaunchSlot.Single("missing"),
            QuickLaunchSlot.Folder(
                name = "Tools",
                apps = listOf(QuickLaunchFolderApp.unlimited("b")),
                symbolIconName = "star",
            ),
            QuickLaunchSlot.Folder(
                name = "Pair",
                apps = listOf(
                    QuickLaunchFolderApp.unlimited("a"),
                    QuickLaunchFolderApp.unlimited("b"),
                ),
                symbolIconName = "home",
            ),
            QuickLaunchSlot.Folder(
                name = "Empty",
                apps = listOf(QuickLaunchFolderApp.unlimited("gone")),
            ),
        )
        val ui = mapSlotsToUi(raw, installed)
        assertEquals(3, ui.size)
        assertEquals(listOf("a"), ui[0].apps.map { it.packageName })
        assertNull(ui[0].folderName)
        assertEquals(listOf("b"), ui[1].apps.map { it.packageName })
        assertNull(ui[1].folderName)
        assertNull(ui[1].folderSymbolIconName)
        assertEquals("Pair", ui[2].folderName)
        assertEquals("home", ui[2].folderSymbolIconName)
        assertEquals(listOf("a", "b"), ui[2].apps.map { it.packageName })
    }

    @Test
    fun reconcileOpenFolder_closesWhenSlotMissingOrNotFolderOrTooSmall() {
        val installed = mapOf("a" to app("a"), "b" to app("b"), "c" to app("c"))
        val open = QuickLaunchFolderOpen(0, listOf(app("a"), app("b")), "F", "star")

        assertNull(reconcileOpenFolder(open, emptyList(), installed))
        assertNull(
            reconcileOpenFolder(
                open,
                listOf(QuickLaunchSlot.Single("a")),
                installed,
            ),
        )
        assertNull(
            reconcileOpenFolder(
                open,
                listOf(
                    QuickLaunchSlot.Folder(
                        "F",
                        listOf(QuickLaunchFolderApp.unlimited("a")),
                    ),
                ),
                installed,
            ),
        )
    }

    @Test
    fun reconcileOpenFolder_refreshesAppsFromSlot() {
        val installed = mapOf("a" to app("a"), "b" to app("b"), "c" to app("c"))
        val open = QuickLaunchFolderOpen(0, listOf(app("a"), app("b")), "Old", null)
        val next = reconcileOpenFolder(
            open,
            listOf(
                QuickLaunchSlot.Folder(
                    name = "New",
                    apps = listOf(
                        QuickLaunchFolderApp.unlimited("a"),
                        QuickLaunchFolderApp.unlimited("b"),
                        QuickLaunchFolderApp.unlimited("c"),
                    ),
                    symbolIconName = "bolt",
                ),
            ),
            installed,
        )
        assertEquals(0, next!!.slotIndex)
        assertEquals("New", next.folderName)
        assertEquals("bolt", next.folderSymbolIconName)
        assertEquals(listOf("a", "b", "c"), next.apps.map { it.packageName })
    }

    @Test
    fun nextFolderAfterAppRemoved_closesWhenOneOrZeroRemain() {
        val folder = QuickLaunchFolderOpen(
            1,
            listOf(app("a"), app("b"), app("c")),
            "F",
        )
        val afterOne = nextFolderAfterAppRemoved(folder, "c")
        assertEquals(listOf("a", "b"), afterOne!!.apps.map { it.packageName })

        assertNull(nextFolderAfterAppRemoved(afterOne, "a"))
        assertNull(
            nextFolderAfterAppRemoved(
                QuickLaunchFolderOpen(0, listOf(app("x")), null),
                "x",
            ),
        )
    }

    @Test
    fun missingStripPackages_filtersUninstalled() {
        val raw = listOf(
            QuickLaunchSlot.Single("a"),
            QuickLaunchSlot.Folder(
                name = "F",
                apps = listOf(
                    QuickLaunchFolderApp.unlimited("b"),
                    QuickLaunchFolderApp.unlimited("gone"),
                ),
            ),
        )
        assertEquals(listOf("gone"), missingStripPackages(raw, setOf("a", "b")))
        assertTrue(missingStripPackages(raw, setOf("a", "b", "gone")).isEmpty())
    }

    @Test
    fun mapSlotsToUi_usesPerPackageResolveWithoutFullCatalog() {
        // Simulates folder binding via resolveApp while the full catalog is still empty/loading.
        val resolvedOnly = mapOf(
            "a" to app("a"),
            "b" to app("b"),
        )
        val raw = listOf(
            QuickLaunchSlot.Folder(
                name = "Pair",
                apps = listOf(
                    QuickLaunchFolderApp.unlimited("a"),
                    QuickLaunchFolderApp.unlimited("b"),
                ),
            ),
        )
        val ui = mapSlotsToUi(raw, resolvedOnly)
        assertEquals(1, ui.size)
        assertEquals(listOf("a", "b"), ui[0].apps.map { it.packageName })
        assertEquals("Pair", ui[0].folderName)
    }

    @Test
    fun shouldPruneUninstalledPackages_requiresLoadedNonEmptyCatalog() {
        assertFalse(shouldPruneUninstalledPackages(catalogLoaded = false, installedPackageCount = 0))
        assertFalse(shouldPruneUninstalledPackages(catalogLoaded = false, installedPackageCount = 10))
        assertFalse(shouldPruneUninstalledPackages(catalogLoaded = true, installedPackageCount = 0))
        assertTrue(shouldPruneUninstalledPackages(catalogLoaded = true, installedPackageCount = 1))
    }

    @Test
    fun catalogDisplayKeepsPreviousUntilRefreshCompletes() {
        val previous = listOf(app("a"), app("b"))
        // While refresh is in flight there is no new snapshot yet — keep showing previous.
        assertEquals(previous, catalogAppsForDisplay(previous, ready = null))
        val next = listOf(app("a"), app("b"), app("c"))
        assertEquals(next, catalogAppsForDisplay(previous, ready = next))
        // Never substitute an empty list mid-refresh via a null ready snapshot.
        assertEquals(previous, catalogAppsForDisplay(previous, ready = null))
    }
}
