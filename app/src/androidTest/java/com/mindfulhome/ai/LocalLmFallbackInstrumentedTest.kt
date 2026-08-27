package com.mindfulhome.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mindfulhome.R
import com.mindfulhome.data.AppDatabase
import com.mindfulhome.data.AppRepository
import com.mindfulhome.locale.LocaleHelper
import com.mindfulhome.model.KarmaManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-emulator path: when the local client returns the canned think failure,
 * the user is told and the script continues — including "N more minutes"
 * becoming an extension for the Are-you-sure confirmation.
 */
@RunWith(AndroidJUnit4::class)
class LocalLmFallbackInstrumentedTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private lateinit var repository: AppRepository
    private lateinit var karmaManager: KarmaManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .fallbackToDestructiveMigration()
            .build()
        repository = AppRepository(db)
        karmaManager = KarmaManager(context, repository)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun onDeviceGenericFailure_announcesThenScriptsAndHonorsMoreMinutes() = runBlocking {
        val client = FailingLocalLmClient(reply = LmPlaygroundSessionLogic.GENERIC_FAILURE)
        val manager = NegotiationManager(
            context = context,
            lmClient = client,
            repository = repository,
            karmaManager = karmaManager,
        )
        val opening = manager.startNudgeNegotiation(
            packageName = "com.example.maps",
            appName = "Maps",
            overrunMinutes = 2,
            nudgeCount = 0,
        )
        val notice = LocaleHelper.wrap(context).getString(R.string.local_ai_fallback_notice)
        assertTrue("opening should announce the local failure: ${opening.responseText}", opening.responseText.contains(notice))
        assertFalse(opening.responseText == LmPlaygroundSessionLogic.GENERIC_FAILURE)
        assertEquals(0, opening.extensionMinutes)

        val reply = manager.reply("5 more minutes to finish this")
        assertEquals(5, reply.extensionMinutes)
        assertTrue(reply.accessGranted)
        assertFalse(reply.responseText == LmPlaygroundSessionLogic.GENERIC_FAILURE)
        manager.endConversation()
    }

    @Test
    fun onDeviceThrow_fallsBackAndHonorsMoreMinutes() = runBlocking {
        val client = FailingLocalLmClient(throwOnSend = LocalLmFailure(LmPlaygroundSessionLogic.GENERIC_FAILURE))
        val manager = NegotiationManager(
            context = context,
            lmClient = client,
            repository = repository,
            karmaManager = karmaManager,
        )
        val opening = manager.startNudgeNegotiation("com.example.mail", "Mail", overrunMinutes = 0, nudgeCount = 1)
        val notice = LocaleHelper.wrap(context).getString(R.string.local_ai_fallback_notice)
        assertTrue(opening.responseText.contains(notice))
        val reply = manager.reply("Can I have 10 minutes?")
        assertEquals(10, reply.extensionMinutes)
        manager.endConversation()
    }
}

private class FailingLocalLmClient(
    private val reply: String = LmPlaygroundSessionLogic.GENERIC_FAILURE,
    private val throwOnSend: LocalLmFailure? = null,
) : LmClient {
    override val modelReady: Boolean = true
    private val handle = Any()

    override fun createConversation(systemInstruction: String, toolSets: List<*>): Any = handle

    override suspend fun sendMessage(conversation: Any, message: String): String {
        throwOnSend?.let { throw it }
        return reply
    }

    override fun closeConversation(conversation: Any) = Unit
}
