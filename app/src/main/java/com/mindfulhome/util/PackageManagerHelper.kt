package com.mindfulhome.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Build
import com.mindfulhome.data.PinnedShortcut
import com.mindfulhome.model.AppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object PackageManagerHelper {

    private data class CachedAppEntry(
        val packageName: String,
        val label: String,
        val isSystemApp: Boolean,
        val iconState: Drawable.ConstantState?,
        val iconFallback: Drawable?,
    )

    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheMutex = Mutex()

    @Volatile
    private var cachedEntries: List<CachedAppEntry>? = null

    @Volatile
    private var cachedLabels: Map<String, String> = emptyMap()

    /** Bumps when [cachedEntries] is replaced; Compose can collect and [peekInstalledApps]. */
    private val _catalogGeneration = MutableStateFlow(0)
    val catalogGeneration: StateFlow<Int> = _catalogGeneration.asStateFlow()

    fun hasCatalog(): Boolean = cachedEntries != null

    fun peekInstalledApps(context: Context): List<AppInfo> =
        cachedEntries?.toAppInfoList(context).orEmpty()

    fun precomputeInstalledApps(context: Context) {
        if (cachedEntries != null) return
        preloadScope.launch {
            getInstalledApps(context)
        }
    }

    suspend fun getInstalledApps(context: Context, forceRefresh: Boolean = false): List<AppInfo> {
        if (!forceRefresh) {
            cachedEntries?.let { return it.toAppInfoList(context) }
        }

        return cacheMutex.withLock {
            if (!forceRefresh) {
                cachedEntries?.let { return@withLock it.toAppInfoList(context) }
            }
            // Load first, then swap — never clear the previous catalog before the new one is ready.
            val loaded = withContext(Dispatchers.IO) {
                loadInstalledApps(context)
            }
            cachedEntries = loaded
            cachedLabels = loaded.associate { it.packageName to it.label }
            _catalogGeneration.value = _catalogGeneration.value + 1
            loaded.toAppInfoList(context)
        }
    }

    /**
     * Resolve a single package for folder/strip tiles without waiting on the full catalog.
     * Prefers the in-memory catalog when present; otherwise queries PackageManager.
     * Returns null if the package is not installed.
     */
    fun resolveApp(context: Context, packageName: String): AppInfo? {
        cachedEntries?.firstOrNull { it.packageName == packageName }?.let { entry ->
            return listOf(entry).toAppInfoList(context).first()
        }
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(appInfo).toString()
            cachedLabels = cachedLabels + (packageName to label)
            AppInfo(
                packageName = packageName,
                label = label,
                icon = pm.getApplicationIcon(appInfo),
                isSystemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            )
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun resolveAppsByPackage(context: Context, packages: Collection<String>): Map<String, AppInfo> =
        packages.mapNotNull { pkg -> resolveApp(context, pkg)?.let { pkg to it } }.toMap()

    private fun loadInstalledApps(context: Context): List<CachedAppEntry> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos: List<ResolveInfo> = pm.queryIntentActivities(mainIntent, 0)

        // One package can expose multiple launcher activities; keep a single row per package
        // so UI keys (e.g. HomeGrid "app:packageName") stay unique.
        return resolveInfos
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { resolveInfo ->
                val icon = resolveInfo.loadIcon(pm)
                CachedAppEntry(
                    packageName = resolveInfo.activityInfo.packageName,
                    label = resolveInfo.loadLabel(pm).toString(),
                    iconState = icon.constantState,
                    iconFallback = if (icon.constantState == null) icon else null,
                    isSystemApp = resolveInfo.activityInfo.applicationInfo.flags and
                            ApplicationInfo.FLAG_SYSTEM != 0
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private fun List<CachedAppEntry>.toAppInfoList(context: Context): List<AppInfo> {
        return map { entry ->
            val icon = entry.iconState
                ?.newDrawable(context.resources)
                ?.mutate()
                ?: entry.iconFallback
            AppInfo(
                packageName = entry.packageName,
                label = entry.label,
                icon = icon,
                isSystemApp = entry.isSystemApp,
            )
        }
    }

    fun launchApp(context: Context, packageName: String, shortcut: PinnedShortcut? = null): Boolean {
        val resolved = shortcut ?: QuickLaunchAppRef.parseShortcut(packageName)
        if (resolved != null) {
            return launchPinnedShortcut(context, resolved)
        }
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } else {
            false
        }
    }

    fun launchPinnedShortcut(context: Context, shortcut: PinnedShortcut): Boolean {
        if (shortcut.id.startsWith("legacy-")) {
            return launchShortcutViaStoredIntent(context, shortcut)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val launcherApps = context.getSystemService(LauncherApps::class.java)
            if (launcherApps != null) {
                val user = android.os.Process.myUserHandle()
                if (tryStartShortcutById(launcherApps, shortcut, user)) return true
                if (launchPinnedShortcutViaQuery(context, launcherApps, shortcut, user)) return true
            }
        }
        return launchShortcutViaStoredIntent(context, shortcut)
    }

    private fun launchShortcutViaStoredIntent(context: Context, shortcut: PinnedShortcut): Boolean {
        val uri = shortcut.intentUri ?: return false
        val intent = try {
            Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
        } catch (_: Exception) {
            return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun tryStartShortcutById(
        launcherApps: LauncherApps,
        shortcut: PinnedShortcut,
        user: android.os.UserHandle,
    ): Boolean {
        return try {
            launcherApps.startShortcut(
                shortcut.packageName,
                shortcut.id,
                null,
                null,
                user,
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun launchPinnedShortcutViaQuery(
        context: Context,
        launcherApps: LauncherApps,
        shortcut: PinnedShortcut,
        user: android.os.UserHandle,
    ): Boolean {
        val info = try {
            launcherApps.getShortcuts(
                LauncherApps.ShortcutQuery().apply {
                    setPackage(shortcut.packageName)
                    setShortcutIds(listOf(shortcut.id))
                    setQueryFlags(
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                            LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST,
                    )
                },
                user,
            )?.firstOrNull()
        } catch (_: Exception) {
            null
        } ?: return false
        return try {
            launcherApps.startShortcut(info, null, null)
            true
        } catch (_: Exception) {
            val intent = info.intent ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }
    }

    fun getAppLabel(context: Context, packageName: String): String {
        cachedLabels[packageName]?.let { return it }

        return try {
            val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(appInfo).toString().also { label ->
                cachedLabels = cachedLabels + (packageName to label)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    fun getAppIcon(context: Context, packageName: String): Drawable? {
        return try {
            context.packageManager.getApplicationIcon(packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }
}
