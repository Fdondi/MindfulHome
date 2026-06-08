package com.mindfulhome.util

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import com.mindfulhome.data.PinnedShortcut
import com.mindfulhome.model.AppInfo

object ShortcutUiHelper {

    fun pinnedShortcutToAppInfo(
        context: Context,
        shortcut: PinnedShortcut,
        installedByPackage: Map<String, AppInfo>,
    ): AppInfo {
        val label = shortcut.label?.takeIf { it.isNotBlank() }
            ?: installedByPackage[shortcut.packageName]?.label
            ?: PackageManagerHelper.getAppLabel(context, shortcut.packageName)
        val icon = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            try {
                val launcherApps = context.getSystemService(LauncherApps::class.java)
                val info = launcherApps?.getShortcuts(
                    LauncherApps.ShortcutQuery().apply {
                        setPackage(shortcut.packageName)
                        setShortcutIds(listOf(shortcut.id))
                        setQueryFlags(
                            LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                                LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST,
                        )
                    },
                    android.os.Process.myUserHandle(),
                )?.firstOrNull()
                info?.let { launcherApps?.getShortcutIconDrawable(it, 0) }
            } catch (_: Exception) {
                null
            }
        } else {
            null
        } ?: installedByPackage[shortcut.packageName]?.icon
        return AppInfo(
            packageName = QuickLaunchAppRef.shortcutKey(shortcut),
            label = label,
            icon = icon,
        )
    }
}
