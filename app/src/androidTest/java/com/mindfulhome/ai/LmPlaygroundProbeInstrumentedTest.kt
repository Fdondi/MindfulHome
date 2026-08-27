package com.mindfulhome.ai

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.druk.lmplayground.api.LmPlaygroundClient
import com.druk.lmplayground.api.model.ApiException
import com.druk.lmplayground.api.model.ChatCompletionRequest
import com.druk.lmplayground.api.model.ChatMessage
import com.druk.lmplayground.api.model.LmpRequestOptions
import com.druk.lmplayground.api.model.Role
import com.druk.lmplayground.api.model.ToolDefinition
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Talks to a real LM Playground install on the emulator/device and dumps why
 * the on-device path would surface [LmPlaygroundSessionLogic.GENERIC_FAILURE].
 *
 * Skips (does not fail the suite) when Playground is not installed. Run via
 * `scripts/test/run_lm_playground_instrumented.sh` and read logcat tag
 * [PROBE_TAG]. Operator notes: [docs/test/lm-playground-emulator.md].
 */
@RunWith(AndroidJUnit4::class)
class LmPlaygroundProbeInstrumentedTest {

    private var client: LmPlaygroundClient? = null

    @After
    fun disconnect() {
        client?.disconnect()
        client = null
    }

    @Test
    fun dumpInstallAndConnectionState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = LmPlaygroundClient(context)
        client = probe
        val targets = probe.discover()
        dump("targets=${targets.joinToString { "${it.packageName}/${it.className}" }}")
        dump("installed=${targets.isNotEmpty()}")
        val info = runBlocking { probe.connect() }
        dump("connectInfo=$info")
        dump("state=${probe.state.value}")
        if (info == null) {
            dump("unavailableReason=${probe.state.value}")
        }
    }

    @Test
    fun simpleChat_explainsFailureInsteadOfGenericThinkMessage() {
        val session = connectedSession()
        val request = ChatCompletionRequest(
            messages = listOf(
                ChatMessage(Role.SYSTEM, "Reply with the single word pong."),
                ChatMessage(Role.USER, "ping"),
            ),
            model = null,
            stream = false,
            tools = emptyList(),
            lmp = LmpRequestOptions(allowLoad = true, clientLabel = "MindfulHomeProbe"),
        )
        val outcome = awaitCompletion(session, request, "simpleChat")
        assertFalse(
            "simple chat produced the generic think failure without a typed error:\n$outcome",
            outcome.isGenericThinkFailure,
        )
    }

    @Test
    fun nudgeToolChat_explainsFailureInsteadOfGenericThinkMessage() {
        val session = connectedSession()
        val grant = ToolDefinition(
            name = "grantExtension",
            description = "Grant extra minutes.",
            parametersSchema = LocalLmToolLogic.intPropertySchema("minutes", "Extra minutes"),
        )
        val request = ChatCompletionRequest(
            messages = listOf(
                ChatMessage(
                    Role.SYSTEM,
                    "The timer expired. If they ask for more time, call grantExtension(minutes). One sentence.",
                ),
                ChatMessage(Role.USER, "5 more minutes to finish this email"),
            ),
            model = null,
            stream = false,
            tools = listOf(grant),
            lmp = LmpRequestOptions(allowLoad = true, clientLabel = "MindfulHomeProbe"),
        )
        val outcome = awaitCompletion(session, request, "nudgeToolChat")
        assertFalse(
            "nudge tool chat produced the generic think failure without a typed error:\n$outcome",
            outcome.isGenericThinkFailure,
        )
    }

    private fun connectedSession(): LmPlaygroundClient {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = LmPlaygroundClient(context)
        client = probe
        assumeTrue(
            "LM Playground is not installed on this emulator/device. " +
                "Install it (and load a model) to diagnose the on-device path.",
            probe.discover().isNotEmpty(),
        )
        val info = runBlocking { probe.connect() }
        dump("connected info=$info state=${probe.state.value}")
        assumeTrue(
            "LM Playground is installed but connect() failed: state=${probe.state.value}",
            info != null,
        )
        return probe
    }

    private fun awaitCompletion(
        probe: LmPlaygroundClient,
        request: ChatCompletionRequest,
        label: String,
    ): ProbeOutcome {
        return try {
            val completion = runBlocking {
                withTimeout(PROBE_TIMEOUT_MS) { probe.chatCompletionAwait(request) }
            }
            val text = completion.message.content.orEmpty()
            val models = runCatching { runBlocking { probe.listModels() } }.getOrNull()
            val outcome = ProbeOutcome(
                label = label,
                text = text,
                finishReason = completion.finishReason,
                errorType = null,
                errorMessage = null,
                errorCode = null,
                exceptionClass = null,
                models = models?.models?.joinToString { "${it.id} loaded=${it.loaded} tools=${it.capabilities.tools}" },
                loadedModel = models?.loadedModel,
                engineBusy = models?.engineBusy,
            )
            dump(outcome.toString())
            outcome
        } catch (e: ApiException) {
            val outcome = ProbeOutcome(
                label = label,
                text = "",
                finishReason = null,
                errorType = e.error.type,
                errorMessage = e.error.message,
                errorCode = e.error.code,
                exceptionClass = e.javaClass.name,
                models = null,
                loadedModel = e.error.loadedModelId,
                engineBusy = null,
            )
            dump(outcome.toString())
            outcome
        } catch (e: Exception) {
            val outcome = ProbeOutcome(
                label = label,
                text = "",
                finishReason = null,
                errorType = null,
                errorMessage = e.message,
                errorCode = null,
                exceptionClass = e.javaClass.name,
                models = null,
                loadedModel = null,
                engineBusy = null,
            )
            dump(outcome.toString())
            outcome
        }
    }

    private fun dump(line: String) {
        Log.i(PROBE_TAG, line)
    }

    private data class ProbeOutcome(
        val label: String,
        val text: String,
        val finishReason: String?,
        val errorType: String?,
        val errorMessage: String?,
        val errorCode: String?,
        val exceptionClass: String?,
        val models: String?,
        val loadedModel: String?,
        val engineBusy: Boolean?,
    ) {
        val isGenericThinkFailure: Boolean
            get() {
                val looksGeneric = text.trim() == LmPlaygroundSessionLogic.GENERIC_FAILURE ||
                    (text.isBlank() && errorType == null && exceptionClass != null)
                return looksGeneric && errorType == null
            }
    }

    companion object {
        const val PROBE_TAG = "LmPlaygroundProbe"
        private const val PROBE_TIMEOUT_MS = 180_000L
    }
}
