package com.mindfulhome.ai.backend

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Vertex AI function declarations for MindfulHome's tools.
 *
 * These are sent as the `tools` field in each backend request so that
 * the Gemini model can call them via native function calling.
 * The format matches the Vertex AI generateContent API schema.
 */
object BackendToolDeclarations {

    /** Tools for the gatekeeper flow (hidden app access). */
    val GATEKEEPER_TOOLS: List<Map<String, JsonElement>> = listOf(
        buildToolsObject(
            function(
                name = "grantAccess",
                description = "Open the hidden app for the user. Call this when you decide to let them use it.",
            )
        )
    )

    /** Tools for the nudge flow (timer expiry). */
    val NUDGE_TOOLS: List<Map<String, JsonElement>> = listOf(
        buildToolsObject(
            function(
                name = "grantExtension",
                description = "Grant the user extra time on their current app. Call this when they give a good reason for needing more time.",
                parameters = buildJsonObject {
                    put("type", "OBJECT")
                    put("properties", buildJsonObject {
                        put("minutes", buildJsonObject {
                            put("type", "INTEGER")
                            put("description", "Number of extra minutes to grant, typically 5 to 15")
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("minutes")) })
                }
            )
        )
    )

    /** Tools for general chat (app launching). */
    val GENERAL_CHAT_TOOLS: List<Map<String, JsonElement>> = listOf(
        buildToolsObject(
            function(
                name = "launchApp",
                description = "Launch an app on the user's phone. Use the exact package name from the hidden apps briefing, or a well-known Android package name.",
                parameters = buildJsonObject {
                    put("type", "OBJECT")
                    put("properties", buildJsonObject {
                        put("packageName", buildJsonObject {
                            put("type", "STRING")
                            put("description", "The Android package name of the app, e.g. com.instagram.android")
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("packageName")) })
                }
            )
        )
    )

    // ── Helpers ──────────────────────────────────────────────────────

    private fun function(
        name: String,
        description: String,
        parameters: JsonElement? = null,
    ): JsonElement = buildJsonObject {
        put("name", name)
        put("description", description)
        if (parameters != null) {
            put("parameters", parameters)
        }
    }

    private fun buildToolsObject(vararg functions: JsonElement): Map<String, JsonElement> {
        return mapOf(
            "functionDeclarations" to buildJsonArray {
                functions.forEach { add(it) }
            }
        )
    }
}
