package com.mindfulhome.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

/**
 * Decides whether a foreground package should be ignored during Quick Launch
 * (system/utility shell) or monitored as a normal app switch.
 *
 * [PreinstalledAppPolicy] known social/media packages always stay monitored
 * (they take precedence over utility heuristics, including IMAGE/VIDEO category).
 * Other pre-installed ([ApplicationInfo.FLAG_SYSTEM]) apps are treated as utilities.
 */
class QuickLaunchUtilityClassifier(
    private val signals: PackageSignals,
    private val selfPackageName: String,
) {
    /**
     * @return human-readable reason if [packageName] should be ignored, or null to monitor.
     */
    fun utilityReason(packageName: String): String? {
        if (packageName.isBlank()) return null
        if (packageName == selfPackageName) return "self"
        // Known social/media apps are never utilities, even if CATEGORY_VIDEO/IMAGE.
        if (PreinstalledAppPolicy.isKnownMediaPackage(packageName)) return null
        if (signals.isInputMethodPackage(packageName)) return "keyboard/IME"

        if (signals.isHomeLauncherPackage(packageName)) return "home launcher"
        if (signals.isSettingsPackage(packageName)) return "settings"
        if (signals.isDefaultDialerPackage(packageName)) return "default dialer"
        if (!signals.hasLaunchIntent(packageName)) return "no launch intent"

        val normalized = packageName.lowercase()
        if (normalized in UTILITY_PACKAGES_EXACT) {
            return "utility exact package"
        }
        val matchedPrefix = UTILITY_PACKAGE_PREFIXES.firstOrNull { normalized.startsWith(it) }
        if (matchedPrefix != null) {
            return "utility package prefix=$matchedPrefix"
        }
        val matchedKeyword = UTILITY_PACKAGE_KEYWORDS.firstOrNull { normalized.contains(it) }
        if (matchedKeyword != null) {
            return "utility package keyword=$matchedKeyword"
        }
        val label = signals.appLabel(packageName).lowercase()
        val matchedLabel = UTILITY_LABEL_KEYWORDS.firstOrNull { label.contains(it) }
        if (matchedLabel != null) {
            return "utility label keyword=$matchedLabel"
        }

        if (signals.isSystemPackage(packageName)) {
            return "preinstalled system"
        }

        return when (signals.applicationCategory(packageName)) {
            ApplicationInfo.CATEGORY_IMAGE -> "media category=IMAGE"
            ApplicationInfo.CATEGORY_VIDEO -> "media category=VIDEO"
            else -> null
        }
    }

    fun isUtility(packageName: String): Boolean = utilityReason(packageName) != null

    /**
     * Platform lookups used by the classifier. Production uses [AndroidPackageSignals];
     * unit tests supply a fake.
     */
    interface PackageSignals {
        fun isInputMethodPackage(packageName: String): Boolean
        fun isHomeLauncherPackage(packageName: String): Boolean
        fun isSettingsPackage(packageName: String): Boolean
        fun isDefaultDialerPackage(packageName: String): Boolean
        fun hasLaunchIntent(packageName: String): Boolean
        fun appLabel(packageName: String): String
        /** [ApplicationInfo.category], or a sentinel when unavailable. */
        fun applicationCategory(packageName: String): Int
        fun isSystemPackage(packageName: String): Boolean
    }

    /**
     * Live [PackageManager]/Role-style lookups with a short TTL cache for
     * home packages, Settings package, dialer, and IME packages.
     */
    class AndroidPackageSignals(
        private val context: Context,
        private val ttlMs: Long = CACHE_TTL_MS,
        private val elapsedRealtime: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    ) : PackageSignals {

        private var imePackages: Set<String> = emptySet()
        private var imeFetchedAtMs: Long = 0L

        private var homePackages: Set<String> = emptySet()
        private var homeFetchedAtMs: Long = 0L

        private var settingsPackage: String? = null
        private var settingsFetchedAtMs: Long = 0L

        private var dialerPackage: String? = null
        private var dialerFetchedAtMs: Long = 0L

        private val pm: PackageManager get() = context.packageManager

        override fun isInputMethodPackage(packageName: String): Boolean {
            refreshImeIfNeeded()
            return packageName in imePackages
        }

        override fun isHomeLauncherPackage(packageName: String): Boolean {
            refreshHomeIfNeeded()
            return packageName in homePackages
        }

        override fun isSettingsPackage(packageName: String): Boolean {
            refreshSettingsIfNeeded()
            return settingsPackage != null && packageName == settingsPackage
        }

        override fun isDefaultDialerPackage(packageName: String): Boolean {
            refreshDialerIfNeeded()
            return dialerPackage != null && packageName == dialerPackage
        }

        override fun hasLaunchIntent(packageName: String): Boolean {
            return try {
                pm.getLaunchIntentForPackage(packageName) != null
            } catch (_: Exception) {
                false
            }
        }

        override fun appLabel(packageName: String): String {
            if (packageName.isEmpty()) return ""
            return try {
                val appInfo = pm.getApplicationInfo(packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                packageName.substringAfterLast('.')
            }
        }

        override fun applicationCategory(packageName: String): Int {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return ApplicationInfo.CATEGORY_UNDEFINED
            }
            return try {
                pm.getApplicationInfo(packageName, 0).category
            } catch (_: Exception) {
                ApplicationInfo.CATEGORY_UNDEFINED
            }
        }

        override fun isSystemPackage(packageName: String): Boolean {
            return try {
                val flags = pm.getApplicationInfo(packageName, 0).flags
                (flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            } catch (_: Exception) {
                false
            }
        }

        private fun refreshImeIfNeeded() {
            val now = elapsedRealtime()
            if (imePackages.isEmpty() || now - imeFetchedAtMs > ttlMs) {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imePackages = imm.inputMethodList.mapTo(mutableSetOf()) { it.packageName }
                imeFetchedAtMs = now
            }
        }

        private fun refreshHomeIfNeeded() {
            val now = elapsedRealtime()
            if (homePackages.isEmpty() || now - homeFetchedAtMs > ttlMs) {
                val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                homePackages = pm.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                    .mapTo(mutableSetOf()) { it.activityInfo.packageName }
                homeFetchedAtMs = now
            }
        }

        private fun refreshSettingsIfNeeded() {
            val now = elapsedRealtime()
            if (settingsFetchedAtMs == 0L || now - settingsFetchedAtMs > ttlMs) {
                settingsPackage = try {
                    val intent = Intent(Settings.ACTION_SETTINGS)
                    pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                        ?.activityInfo?.packageName
                } catch (_: Exception) {
                    null
                }
                settingsFetchedAtMs = now
            }
        }

        private fun refreshDialerIfNeeded() {
            val now = elapsedRealtime()
            if (dialerFetchedAtMs == 0L || now - dialerFetchedAtMs > ttlMs) {
                dialerPackage = try {
                    val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    telecom?.defaultDialerPackage
                } catch (_: Exception) {
                    null
                }
                dialerFetchedAtMs = now
            }
        }

        companion object {
            const val CACHE_TTL_MS = 5 * 60_000L
        }
    }

    companion object {
        val UTILITY_PACKAGES_EXACT = setOf(
            "com.android.camera",
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.photos",
            "com.android.gallery3d",
            "com.google.android.documentsui",
            "com.android.documentsui",
            "com.android.providers.media.module",
            "com.android.permissioncontroller",
            "com.android.systemui",
        )
        val UTILITY_PACKAGE_PREFIXES = setOf(
            "com.android.camera",
            "com.android.gallery",
            "com.google.android.apps.photos",
            "com.google.android.documentsui",
            "com.android.documentsui",
            "com.android.providers.media",
            "com.android.providers.downloads",
        )
        val UTILITY_PACKAGE_KEYWORDS = setOf(
            "camera",
            "gallery",
            "photos",
            "media",
            "picker",
            "documentsui",
            "filemanager",
            "files",
        )
        val UTILITY_LABEL_KEYWORDS = setOf(
            "camera",
            "gallery",
            "photos",
            "photo",
            "media",
            "files",
            "file manager",
            "file picker",
        )
    }
}
