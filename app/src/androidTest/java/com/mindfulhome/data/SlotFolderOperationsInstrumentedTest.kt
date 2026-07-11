package com.mindfulhome.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Parity checks for “folder” behavior: favorites strip and QuickLaunch both support
 * removing one app from a multi-app group and extracting one app into its own adjacent slot.
 */
@RunWith(AndroidJUnit4::class)
class SlotFolderOperationsInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AppRepository

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .fallbackToDestructiveMigration()
            .build()
        repo = AppRepository(db)
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun quickLaunch_removeFromFolder_keepsNamedFolderWithOneApp() = runBlocking {
        repo.addIntentFolder("Work", listOf("com.a", "com.b"))
        repo.removeFromQuickLaunch("com.b")

        val slots = repo.quickLaunchSlots().first()
        assertEquals(1, slots.size)
        assertTrue(slots[0] is QuickLaunchSlot.Folder)
        val folder = slots[0] as QuickLaunchSlot.Folder
        assertEquals("Work", folder.name)
        assertEquals(listOf("com.a"), folder.packageNames())
    }

    @Test
    fun mergeTimedAppIntoFolder_persistsLimitMinutes() = runBlocking {
        repo.addIntentFolder("Work")
        repo.mergePackageIntoQuickLaunchAt(0, "com.timed", limitMinutes = 7)
        repo.mergePackageIntoQuickLaunchAt(0, "com.unlimited", limitMinutes = null)

        val folder = repo.quickLaunchSlots().first() as QuickLaunchSlot.Folder
        assertEquals(7, folder.limitMinutesFor("com.timed"))
        assertEquals(null, folder.limitMinutesFor("com.unlimited"))
        assertEquals(listOf("com.unlimited"), folder.flattenAllowedPackages())
    }

    @Test
    fun quickLaunch_extractFromFolder_producesTwoFoldersInOrder() = runBlocking {
        repo.addIntentFolder("Work", listOf("com.a", "com.b"))
        repo.extractQuickLaunchAppToOwnSlot("com.b")

        val slots = repo.quickLaunchSlots().first()
        assertEquals(2, slots.size)
        assertTrue(slots[0] is QuickLaunchSlot.Folder)
        assertTrue(slots[1] is QuickLaunchSlot.Folder)
        assertEquals(listOf("com.a"), (slots[0] as QuickLaunchSlot.Folder).packageNames())
        assertEquals(listOf("com.b"), (slots[1] as QuickLaunchSlot.Folder).packageNames())
    }

    @Test
    fun favorites_removeFromFolder_leavesOnePackageInSlot() = runBlocking {
        repo.addToFavorites("com.a")
        repo.addToFavorites("com.b")
        repo.mergeFavoritesSlots(fromUiIndex = 1, intoUiIndex = 0)
        repo.removeFromFavorites("com.b")

        val slots = repo.favoritesSlots().first()
        assertEquals(1, slots.size)
        assertTrue(slots[0] is QuickLaunchSlot.Single)
        assertEquals("com.a", (slots[0] as QuickLaunchSlot.Single).packageName)
    }

    @Test
    fun favorites_extractFromFolder_insertsOwnSlotAndShiftsLaterSlots() = runBlocking {
        repo.addToFavorites("com.a")
        repo.addToFavorites("com.b")
        repo.mergeFavoritesSlots(fromUiIndex = 1, intoUiIndex = 0)
        repo.addToFavorites("com.c")
        repo.extractFavoritesAppToOwnSlot("com.b")

        val slots = repo.favoritesSlots().first()
        assertEquals(3, slots.size)
        assertTrue(slots[0] is QuickLaunchSlot.Single)
        assertTrue(slots[1] is QuickLaunchSlot.Single)
        assertTrue(slots[2] is QuickLaunchSlot.Single)
        assertEquals("com.a", (slots[0] as QuickLaunchSlot.Single).packageName)
        assertEquals("com.b", (slots[1] as QuickLaunchSlot.Single).packageName)
        assertEquals("com.c", (slots[2] as QuickLaunchSlot.Single).packageName)
    }

    @Test
    fun favorites_extractFromFolder_twoPackagesOnly() = runBlocking {
        repo.addToFavorites("com.a")
        repo.addToFavorites("com.b")
        repo.mergeFavoritesSlots(fromUiIndex = 1, intoUiIndex = 0)
        repo.extractFavoritesAppToOwnSlot("com.b")

        val slots = repo.favoritesSlots().first()
        assertEquals(2, slots.size)
        assertTrue(slots[0] is QuickLaunchSlot.Single)
        assertTrue(slots[1] is QuickLaunchSlot.Single)
        assertEquals("com.a", (slots[0] as QuickLaunchSlot.Single).packageName)
        assertEquals("com.b", (slots[1] as QuickLaunchSlot.Single).packageName)
    }
}
