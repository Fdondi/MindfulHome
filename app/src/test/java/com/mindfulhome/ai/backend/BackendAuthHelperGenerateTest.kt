package com.mindfulhome.ai.backend

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BackendAuthHelperGenerateTest {

    @Test
    fun generateWithAutoRefresh_usesInjectedGenerate_noNetwork() = runBlocking {
        var generateCalls = 0
        val helper = BackendAuthHelper(
            signInForExchange = { null },
            getSessionToken = { "session-token" },
            saveSessionToken = { _, _ -> },
            clearSessionToken = { },
            isSessionExpiringSoon = { false },
            generate = { token, model, contents, tools ->
                generateCalls++
                assertEquals("session-token", token)
                assertEquals("gemini-test", model)
                assertEquals(1, contents.size)
                assertEquals(null, tools)
                BackendClient.GenerateResponse(
                    result = "fixture reply",
                    function_calls = listOf(BackendClient.FunctionCall("grantAccess")),
                )
            },
        )

        val response = helper.generateWithAutoRefresh(
            model = "gemini-test",
            contents = listOf(
                BackendClient.BackendContent(
                    role = "user",
                    parts = listOf(BackendClient.BackendPart("hello")),
                ),
            ),
        )

        assertEquals(1, generateCalls)
        assertEquals("fixture reply", response.result)
        assertEquals("grantAccess", response.function_calls.single().name)
    }
}
