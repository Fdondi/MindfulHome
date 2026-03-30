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

@RunWith(AndroidJUnit4::class)
class QuickLaunchRepositoryInstrumentedTest {

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
    fun mergeThenRenameFolder_roundTripsThroughKv() = runBlocking {
        repo.addToQuickLaunch("com.one")
        repo.addToQuickLaunch("com.two")
        repo.mergeQuickLaunchSlots(fromUiIndex = 1, intoUiIndex = 0)
        repo.setQuickLaunchFolderName("com.one", "Label")

        val slots = repo.quickLaunchSlots().first()
        val folder = slots.single() as QuickLaunchSlot.Folder
        assertEquals("Label", folder.name)
        assertEquals(listOf("com.one", "com.two"), folder.apps)
    }

    @Test
    fun removeFromQuickLaunch_updatesStoredLayout() = runBlocking {
        repo.addToQuickLaunch("a")
        repo.addToQuickLaunch("b")
        repo.removeFromQuickLaunch("a")
        val slots = repo.quickLaunchSlots().first()
        assertEquals(1, slots.size)
        assertTrue(slots[0] is QuickLaunchSlot.Single)
        assertEquals("b", (slots[0] as QuickLaunchSlot.Single).packageName)
    }

    @Test
    fun moveSlot_reorders() = runBlocking {
        repo.addToQuickLaunch("first")
        repo.addToQuickLaunch("second")
        repo.moveQuickLaunchSlot(fromUiIndex = 0, toUiIndex = 1)
        val slots = repo.quickLaunchSlots().first()
        assertEquals("second", (slots[0] as QuickLaunchSlot.Single).packageName)
        assertEquals("first", (slots[1] as QuickLaunchSlot.Single).packageName)
    }
}
