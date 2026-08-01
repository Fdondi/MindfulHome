package com.mindfulhome.ui.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.mindfulhome.data.HomeLayoutItem
import com.mindfulhome.model.AppInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLogicTest {

    private fun app(pkg: String, label: String = pkg) = AppInfo(pkg, label, null)

    @Test
    fun negativeKarmaPackageSet_filtersOptedOutAndNonNegative() {
        val map = mapOf(
            "a" to SimpleKarmaScore("a", -2),
            "b" to SimpleKarmaScore("b", -1, isOptedOut = true),
            "c" to SimpleKarmaScore("c", 3),
        )
        assertEquals(setOf("a"), negativeKarmaPackageSet(map))
    }

    @Test
    fun shouldRequestAi_membership() {
        assertTrue(shouldRequestAi("x", setOf("x")))
        assertFalse(shouldRequestAi("y", setOf("x")))
    }

    @Test
    fun buildGridItems_ordersByPositionThenLabel() {
        val apps = listOf(app("b", "Beta"), app("a", "Alpha"), app("c", "Charlie"))
        val layout = listOf(HomeLayoutItem("c", position = 0, isDocked = false, dockPosition = 0))
        val items = buildGridItems(apps, layout)
        assertEquals(listOf("c", "a", "b"), items.map { (it as HomeGridItem.AppEntry).appInfo.packageName })
    }

    @Test
    fun resolveHomeDropAction_variants() {
        val appItem = HomeGridItem.AppEntry(app("p"), 0)
        assertTrue(
            resolveHomeDropAction(appItem, DropResult(DropTarget.Dock, false))
                is HomeDropAction.AddToFavorites,
        )
        assertTrue(
            resolveHomeDropAction(appItem, DropResult(DropTarget.OnFavoriteSlot(2), false))
                is HomeDropAction.MergeIntoFavoriteSlot,
        )
        assertTrue(
            resolveHomeDropAction(appItem, DropResult(DropTarget.OnItem("other"), false))
                is HomeDropAction.ReorderGrid,
        )
        assertEquals(
            HomeDropAction.None,
            resolveHomeDropAction(appItem, DropResult(DropTarget.None, false)),
        )
    }

    @Test
    fun findHomeDropTargetAt_priorityFavoritesThenDockThenItem() {
        val fav = mapOf(1 to Rect(0f, 0f, 10f, 10f))
        val dock = Rect(0f, 20f, 100f, 40f)
        val items = mapOf("a" to Rect(0f, 50f, 20f, 70f), "b" to Rect(30f, 50f, 50f, 70f))
        assertEquals(
            DropTarget.OnFavoriteSlot(1),
            findHomeDropTargetAt(Offset(5f, 5f), fav, dock, items, "a"),
        )
        assertEquals(
            DropTarget.Dock,
            findHomeDropTargetAt(Offset(50f, 30f), emptyMap(), dock, items, "a"),
        )
        assertEquals(
            DropTarget.OnItem("b"),
            findHomeDropTargetAt(Offset(40f, 60f), emptyMap(), Rect.Zero, items, "a"),
        )
        assertEquals(
            DropTarget.None,
            findHomeDropTargetAt(Offset(40f, 60f), emptyMap(), Rect.Zero, items, null),
        )
    }

    @Test
    fun applyGridReorder_and_dropAction() = runBlocking {
        assertEquals(listOf("b", "a", "c"), applyGridReorder(listOf("a", "b", "c"), "a", "b"))
        assertNull(applyGridReorder(listOf("a"), "a", "a"))
        var favorited: String? = null
        applyHomeDropAction(
            HomeDropAction.AddToFavorites("p"),
            favoritePackages = emptySet(),
            gridKeys = emptyList(),
            addToFavorites = { favorited = it },
            mergeIntoFavorite = { _, _ -> },
            reorderGrid = {},
        )
        assertEquals("p", favorited)
        assertTrue(favoritesStripHighlighted(DropTarget.Dock))
        assertEquals("k", gridHoverKey(DropTarget.OnItem("k")))
        assertTrue(shouldComputeSuggestedApps("why", allAppsEmpty = false))
        assertFalse(shouldComputeSuggestedApps("", allAppsEmpty = false))
    }

    @Test
    fun layoutUpdatesFromGrid_mapsPositions() {
        val items = listOf(
            HomeGridItem.AppEntry(app("a"), 9),
            HomeGridItem.AppEntry(app("b"), 8),
        )
        val updates = layoutUpdatesFromGrid(items)
        assertEquals(0, updates[0].position)
        assertEquals("a", updates[0].packageName)
        assertEquals(1, updates[1].position)
    }
}
