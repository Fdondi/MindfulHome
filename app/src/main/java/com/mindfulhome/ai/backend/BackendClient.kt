package com.mindfulhome.ai.backend

import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * HTTP client that talks to the shared Gemini backend.
 * Mirrors distraction-linter's BackendClient but adds a [tools] field
 * so each app can send its own function declarations.
 */
object BackendClient {

    private const val TAG = "BackendClient"

    // Same backend as distraction-linter — it's app-agnostic now
    private const val BASE_URL =
        "https://my-gemini-backend-834588824353.europe-west1.run.app"

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    // ── Request / response models ────────────────────────────────────

    @Serializable
    data class GenerateRequest(
        val model: String = "default",
        val prompt: String? = null,
        val contents: List<BackendContent> = emptyList(),
        val tools: List<Map<String, JsonElement>>? = null,
    )

    @Serializable
    data class BackendContent(
        val role: String,
        val parts: List<BackendPart>,
    )

    @Serializable
    data class BackendPart(val text: String)

    @Serializable
    data class FunctionCall(
        val name: String,
        val args: Map<String, JsonElement> = emptyMap(),
    )

    @Serializable
    data class GenerateResponse(
        val result: String? = null,
        val function_calls: List<FunctionCall> = emptyList(),
    )

    @Serializable
    data class ExchangeTokenResponse(
        val token: String,
        val expiresAt: String,
    )

    @Serializable
    data class AuthStatusResponse(val status: String)

    @Serializable
    data class ModelInfo(
        val id: String,
        val label: String,
        val description: String,
    )

    @Serializable
    data class ModelsResponse(
        val models: List<ModelInfo>,
        val default: String,
    )

    // ── API calls ────────────────────────────────────────────────────

    fun generate(
        token: String,
        model: String,
        contents: List<BackendContent>,
        tools: List<Map<String, JsonElement>>? = null,
    ): GenerateResponse {
        val body = json.encodeToString(
            GenerateRequest(model = model, contents = contents, tools = tools)
        )

        val request = Request.Builder()
            .url("$BASE_URL/api/generate")
            .addHeader("Authorization", "Bearer $token")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw BackendHttpException(
                statusCode = response.code,
                message = parseErrorMessage(responseBody, response.code),
                code = parseErrorCode(responseBody),
            )
        }

        return json.decodeFromString<GenerateResponse>(responseBody)
    }

    fun exchangeToken(googleIdToken: String): ExchangeTokenResponse {
        val request = Request.Builder()
            .url("$BASE_URL/api/auth/exchange")
            .addHeader("Authorization", "Bearer $googleIdToken")
            .post("".toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw BackendHttpException(
                statusCode = response.code,
                message = parseErrorMessage(responseBody, response.code),
                code = parseErrorCode(responseBody),
            )
        }

        return json.decodeFromString<ExchangeTokenResponse>(responseBody)
    }

    fun checkAuthStatus(token: String) {
        val request = Request.Builder()
            .url("$BASE_URL/api/auth/status")
            .addHeader("Authorization", "Bearer $token")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw BackendHttpException(
                statusCode = response.code,
                message = parseErrorMessage(responseBody, response.code),
                code = parseErrorCode(responseBody),
            )
        }
    }

    /**
     * Fetches available AI models from the backend. No auth required.
     */
    fun getModels(): ModelsResponse {
        val request = Request.Builder()
            .url("$BASE_URL/api/models")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            throw BackendHttpException(
                statusCode = response.code,
                message = parseErrorMessage(responseBody, response.code),
                code = parseErrorCode(responseBody),
            )
        }

        return json.decodeFromString<ModelsResponse>(responseBody)
    }

    fun parseExpiresAt(isoString: String): Long {
        return try {
            Instant.from(DateTimeFormatter.ISO_DATE_TIME.parse(isoString))
                .toEpochMilli()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse expiresAt '$isoString', defaulting to 30 days", e)
            System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000
        }
    }

    // ── Error parsing ────────────────────────────────────────────────

    private fun parseErrorMessage(body: String, code: Int): String {
        return try {
            val obj = json.parseToJsonElement(body).jsonObject
            val detail = obj["detail"]
            when (detail) {
                is JsonPrimitive -> detail.contentOrNull ?: "HTTP $code"
                is JsonObject -> detail["message"]?.let {
                    (it as? JsonPrimitive)?.contentOrNull
                } ?: "HTTP $code"
                else -> "HTTP $code"
            }
        } catch (_: Exception) {
            "HTTP $code"
        }
    }

    private fun parseErrorCode(body: String): String? {
        return try {
            val obj = json.parseToJsonElement(body).jsonObject
            val detail = obj["detail"]
            if (detail is JsonObject) {
                (detail["code"] as? JsonPrimitive)?.contentOrNull
            } else null
        } catch (_: Exception) {
            null
        }
    }
}

class BackendHttpException(
    val statusCode: Int,
    message: String,
    val code: String? = null,
) : IOException(message)
