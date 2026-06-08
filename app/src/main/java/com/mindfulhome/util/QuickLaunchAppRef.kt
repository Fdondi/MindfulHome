package com.mindfulhome.util

import com.mindfulhome.data.PinnedShortcut

/** Encodes pinned shortcuts as synthetic launch keys in [com.mindfulhome.model.AppInfo.packageName]. */
object QuickLaunchAppRef {
    private const val PREFIX = "sc:"

    fun shortcutKey(shortcut: PinnedShortcut): String = shortcutKey(shortcut.packageName, shortcut.id)

    fun shortcutKey(packageName: String, shortcutId: String): String = "$PREFIX$packageName/$shortcutId"

    fun isShortcutKey(key: String): Boolean = key.startsWith(PREFIX)

    fun parseShortcut(key: String): PinnedShortcut? {
        if (!key.startsWith(PREFIX)) return null
        val rest = key.removePrefix(PREFIX)
        val slash = rest.indexOf('/')
        if (slash <= 0 || slash >= rest.lastIndex) return null
        return PinnedShortcut(
            packageName = rest.substring(0, slash),
            id = rest.substring(slash + 1),
        )
    }

    fun ownerPackage(key: String): String = parseShortcut(key)?.packageName ?: key
}
