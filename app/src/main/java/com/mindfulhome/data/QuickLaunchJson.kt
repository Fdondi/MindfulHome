package com.mindfulhome.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object QuickLaunchJson {
    const val KV_KEY = "quick_launch_v1"

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(slots: List<QuickLaunchSlot>): String = encodeSlots(slots, intentMode = false)

    fun encodeIntentSlots(slots: List<QuickLaunchSlot>): String = encodeSlots(slots, intentMode = true)

    private fun encodeSlots(slots: List<QuickLaunchSlot>, intentMode: Boolean): String = buildJsonArray {
        for (slot in slots) {
            when (slot) {
                is QuickLaunchSlot.Single -> {
                    if (intentMode) {
                        add(
                            buildJsonObject {
                                put("apps", JsonArray(listOf(JsonPrimitive(slot.packageName))))
                            },
                        )
                    } else {
                        add(JsonPrimitive(slot.packageName))
                    }
                }
                is QuickLaunchSlot.Folder -> add(
                    buildJsonObject {
                        val n = slot.name?.trim()?.takeIf { it.isNotEmpty() }
                        if (n != null) put("name", n)
                        val sym = slot.symbolIconName?.trim()?.takeIf { it.isNotEmpty() }
                        if (sym != null) put("symbolIcon", sym)
                        put(
                            "apps",
                            JsonArray(slot.apps.map { JsonPrimitive(it) }),
                        )
                    },
                )
            }
        }
    }.toString()

    fun decode(raw: String?): List<QuickLaunchSlot> = decodeSlots(raw, intentMode = false)

    fun decodeIntentSlots(raw: String?): List<QuickLaunchSlot> = decodeSlots(raw, intentMode = true)

    private fun decodeSlots(raw: String?, intentMode: Boolean): List<QuickLaunchSlot> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = try {
            json.parseToJsonElement(raw).jsonArray
        } catch (_: Exception) {
            return emptyList()
        }
        return arr.mapNotNull { el ->
            when {
                el is JsonPrimitive && el.isString ->
                    QuickLaunchSlot.Single(el.content)
                el is JsonObject -> {
                    val apps = el["apps"]?.jsonArray?.map { it.jsonPrimitive.content }
                        ?.filter { it.isNotBlank() }
                        ?.distinct()
                        ?: emptyList()
                    val name = el["name"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    val symbolIcon =
                        el["symbolIcon"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    when {
                        apps.isEmpty() && name == null -> return@mapNotNull null
                        intentMode || apps.size != 1 || name != null ->
                            QuickLaunchSlot.Folder(name, apps, symbolIcon)
                        else -> QuickLaunchSlot.Single(apps[0])
                    }
                }
                else -> null
            }
        }
    }
}
