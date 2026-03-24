package com.mindfulhome.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import com.mindfulhome.model.AppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
            val loaded = withContext(Dispatchers.IO) {
                loadInstalledApps(context)
            }
            cachedEntries = loaded
            cachedLabels = loaded.associate { it.packageName to it.label }
            loaded.toAppInfoList(context)
        }
    }

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
                            android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
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

    fun launchApp(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        return if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } else {
            false
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
}
