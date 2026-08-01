package com.mindfulhome.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object QuickLaunchJson {
    const val KV_KEY = "quick_launch_v1"

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(slots: List<QuickLaunchSlot>): String = encodeSlots(slots, intentMode = false)

    fun encodeIntentSlots(slots: List<QuickLaunchSlot>): String = encodeSlots(slots, intentMode = true)

    private fun encodeFolderApp(app: QuickLaunchFolderApp): JsonObject = buildJsonObject {
        put("pkg", app.packageName)
        app.limitMinutes?.let { put("limitMinutes", it) }
    }

    private fun encodeSlots(slots: List<QuickLaunchSlot>, intentMode: Boolean): String = buildJsonArray {
        for (slot in slots) {
            when (slot) {
                is QuickLaunchSlot.Single -> {
                    if (intentMode) {
                        add(
                            buildJsonObject {
                                put(
                                    "apps",
                                    JsonArray(
                                        listOf(
                                            JsonPrimitive(slot.packageName),
                                        ),
                                    ),
                                )
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
                            JsonArray(
                                slot.apps.map { app ->
                                    if (app.isUnlimited) {
                                        JsonPrimitive(app.packageName)
                                    } else {
                                        encodeFolderApp(app)
                                    }
                                },
                            ),
                        )
                        if (slot.shortcuts.isNotEmpty()) {
                            put(
                                "shortcuts",
                                JsonArray(
                                    slot.shortcuts.map { shortcut ->
                                        buildJsonObject {
                                            put("pkg", shortcut.packageName)
                                            put("id", shortcut.id)
                                            shortcut.label?.trim()?.takeIf { it.isNotEmpty() }?.let { put("label", it) }
                                            shortcut.intentUri?.trim()?.takeIf { it.isNotEmpty() }?.let { put("intentUri", it) }
                                        }
                                    },
                                ),
                            )
                        }
                    },
                )
            }
        }
    }.toString()

    fun decode(raw: String?): List<QuickLaunchSlot> = decodeSlots(raw, intentMode = false)

    fun decodeIntentSlots(raw: String?): List<QuickLaunchSlot> = decodeSlots(raw, intentMode = true)

    private fun decodeFolderApp(el: kotlinx.serialization.json.JsonElement): QuickLaunchFolderApp? {
        return when {
            el is JsonPrimitive && el.isString -> {
                val pkg = el.content.trim()
                if (pkg.isBlank()) null else QuickLaunchFolderApp.unlimited(pkg)
            }
            el is JsonObject -> {
                val pkg = el["pkg"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (pkg.isBlank()) return null
                val limit = el["limitMinutes"]?.jsonPrimitive?.intOrNull
                if (limit != null && limit > 0) {
                    QuickLaunchFolderApp.timed(pkg, limit)
                } else {
                    QuickLaunchFolderApp.unlimited(pkg)
                }
            }
            else -> null
        }
    }

    private fun decodeSlots(raw: String?, intentMode: Boolean): List<QuickLaunchSlot> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = try {
            json.parseToJsonElement(raw).jsonArray
        } catch (_: Exception) {
            return emptyList()
        }
        return arr.mapNotNull { el -> decodeSlotElement(el, intentMode) }
    }

    private fun decodeSlotElement(
        el: kotlinx.serialization.json.JsonElement,
        intentMode: Boolean,
    ): QuickLaunchSlot? = when {
        el is JsonPrimitive && el.isString -> QuickLaunchSlot.Single(el.content)
        el is JsonObject -> decodeObjectSlot(el, intentMode)
        else -> null
    }

    private fun decodeObjectSlot(el: JsonObject, intentMode: Boolean): QuickLaunchSlot? {
        val apps = decodeObjectApps(el)
        val name = optionalTrimmedString(el, "name")
        val symbolIcon = optionalTrimmedString(el, "symbolIcon")
        val shortcuts = decodeShortcuts(el["shortcuts"]?.jsonArray)
        return materializeObjectSlot(apps, name, symbolIcon, shortcuts, intentMode)
    }

    private fun decodeObjectApps(el: JsonObject): List<QuickLaunchFolderApp> =
        el["apps"]?.jsonArray?.mapNotNull { decodeFolderApp(it) }
            ?.let { normalizeFolderApps(it) }
            ?: emptyList()

    private fun optionalTrimmedString(el: JsonObject, key: String): String? =
        el[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun materializeObjectSlot(
        apps: List<QuickLaunchFolderApp>,
        name: String?,
        symbolIcon: String?,
        shortcuts: List<PinnedShortcut>,
        intentMode: Boolean,
    ): QuickLaunchSlot? {
        if (isEmptyAnonymousFolder(apps, shortcuts, name)) return null
        if (shouldKeepAsFolder(intentMode, apps, name, shortcuts)) {
            return QuickLaunchSlot.Folder(name, apps, symbolIcon, shortcuts)
        }
        return QuickLaunchSlot.Single(apps[0].packageName)
    }

    private fun isEmptyAnonymousFolder(
        apps: List<QuickLaunchFolderApp>,
        shortcuts: List<PinnedShortcut>,
        name: String?,
    ): Boolean = apps.isEmpty() && shortcuts.isEmpty() && name == null

    private fun shouldKeepAsFolder(
        intentMode: Boolean,
        apps: List<QuickLaunchFolderApp>,
        name: String?,
        shortcuts: List<PinnedShortcut>,
    ): Boolean = intentMode || apps.size != 1 || name != null || shortcuts.isNotEmpty()

    private fun decodeShortcuts(
        arr: kotlinx.serialization.json.JsonArray?,
    ): List<PinnedShortcut> {
        if (arr == null) return emptyList()
        return arr.mapNotNull { shortcutEl ->
            val obj = shortcutEl.jsonObject
            val pkg = obj["pkg"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val id = obj["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (pkg.isBlank() || id.isBlank()) return@mapNotNull null
            val label = obj["label"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val intentUri = obj["intentUri"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            PinnedShortcut(pkg, id, label, intentUri)
        }
    }
}
