package com.mindfulhome.ai

import com.druk.lmplayground.api.model.ApiError
import com.druk.lmplayground.api.model.ErrorType
import com.druk.lmplayground.api.model.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LmPlaygroundSessionLogicTest {

    private val grant = ToolDefinition(
        name = "grantAccess",
        description = "Open the app",
        parametersSchema = LocalLmToolLogic.EMPTY_OBJECT_SCHEMA,
    )

    @Test
    fun toolsToSend_omitsWhenLoadedModelLacksTools() {
        assertEquals(emptyList<ToolDefinition>(), LmPlaygroundSessionLogic.toolsToSend(listOf(grant), false))
    }

    @Test
    fun toolsToSend_keepsWhenLoadedSupportsOrUnknown() {
        assertEquals(listOf(grant), LmPlaygroundSessionLogic.toolsToSend(listOf(grant), true))
        assertEquals(listOf(grant), LmPlaygroundSessionLogic.toolsToSend(listOf(grant), null))
        assertEquals(emptyList<ToolDefinition>(), LmPlaygroundSessionLogic.toolsToSend(emptyList(), true))
    }

    @Test
    fun userFacingError_usesServerMessageAndFallbacks() {
        val refused = ApiError(
            message = "The loaded model 'Gemma 3 1B' does not support tools.",
            type = ErrorType.CAPABILITY_UNAVAILABLE,
        )
        assertEquals(refused.message, LmPlaygroundSessionLogic.userFacingError(refused))

        val denied = ApiError(message = "", type = ErrorType.PERMISSION_DENIED)
        assertTrue(LmPlaygroundSessionLogic.userFacingError(denied).contains("Allow other apps"))

        val busy = ApiError(message = "", type = ErrorType.ENGINE_BUSY)
        assertTrue(LmPlaygroundSessionLogic.userFacingError(busy).contains("busy"))

        val unavailable = ApiError(message = "", type = ErrorType.ENGINE_UNAVAILABLE)
        assertTrue(LmPlaygroundSessionLogic.userFacingError(unavailable).contains("disconnected"))

        val capability = ApiError(message = "", type = ErrorType.CAPABILITY_UNAVAILABLE)
        assertTrue(LmPlaygroundSessionLogic.userFacingError(capability).contains("cannot handle"))
    }

    @Test
    fun isUnusableLocalReply_blankAndGeneric() {
        assertTrue(LmPlaygroundSessionLogic.isUnusableLocalReply(""))
        assertTrue(LmPlaygroundSessionLogic.isUnusableLocalReply("   "))
        assertTrue(LmPlaygroundSessionLogic.isUnusableLocalReply(LmPlaygroundSessionLogic.GENERIC_FAILURE))
        assertFalse(LmPlaygroundSessionLogic.isUnusableLocalReply("Wrap up soon."))
    }

    @Test
    fun announceLocalFailureThenScript_joinsOrFallsBack() {
        assertEquals(
            "notice\n\nscript",
            LmPlaygroundSessionLogic.announceLocalFailureThenScript("notice", "script"),
        )
        assertEquals("notice", LmPlaygroundSessionLogic.announceLocalFailureThenScript("notice", "  "))
        assertEquals("script", LmPlaygroundSessionLogic.announceLocalFailureThenScript(null, "script"))
        assertEquals("", LmPlaygroundSessionLogic.announceLocalFailureThenScript("  ", ""))
    }
}
