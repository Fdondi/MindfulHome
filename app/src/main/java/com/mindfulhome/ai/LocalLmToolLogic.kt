package com.mindfulhome.ai

import com.druk.lmplayground.api.model.ToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * JSON helpers for on-device tools executed in-process against LM Playground.
 */
object LocalLmToolLogic {

    const val EMPTY_OBJECT_SCHEMA = """{"type":"object","properties":{}}"""

    fun tool(
        name: String,
        description: String,
        parametersSchema: String = EMPTY_OBJECT_SCHEMA,
    ): ToolDefinition = ToolDefinition(
        name = name,
        description = description,
        parametersSchema = parametersSchema,
    )

    fun intPropertySchema(property: String, description: String): String =
        """{"type":"object","properties":{"$property":{"type":"integer","description":"$description"}},"required":["$property"]}"""

    fun stringPropertySchema(property: String, description: String): String =
        """{"type":"object","properties":{"$property":{"type":"string","description":"$description"}},"required":["$property"]}"""

    fun intArg(argumentsJson: String, key: String, default: Int): Int {
        val primitive = jsonObject(argumentsJson)?.get(key)?.jsonPrimitive ?: return default
        return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull() ?: default
    }

    fun stringArg(argumentsJson: String, key: String, default: String = ""): String {
        val primitive = jsonObject(argumentsJson)?.get(key) as? JsonPrimitive ?: return default
        return primitive.contentOrNull ?: default
    }

    fun objectResult(vararg pairs: Pair<String, Any>): String = buildJsonObject {
        pairs.forEach { (key, value) ->
            when (value) {
                is Int -> put(key, value)
                is Boolean -> put(key, value)
                else -> put(key, value.toString())
            }
        }
    }.toString()

    fun unknownToolResult(name: String): String =
        objectResult("status" to "unknown_tool", "name" to name)

    private fun jsonObject(argumentsJson: String): JsonObject? =
        runCatching { Json.parseToJsonElement(argumentsJson) as? JsonObject }.getOrNull()
}
