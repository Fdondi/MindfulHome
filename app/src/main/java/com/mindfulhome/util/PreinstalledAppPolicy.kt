package com.mindfulhome.util

import com.mindfulhome.model.AppInfo

/**
 * Pre-installed apps are treated as unrestricted (utility / opt-out candidates)
 * unless they are known social/media packages that should stay monitored.
 */
object PreinstalledAppPolicy {

    /** Exact package names that stay monitored even when FLAG_SYSTEM. */
    val KNOWN_MEDIA_PACKAGES_EXACT: Set<String> = setOf(
        "com.instagram.android",
        "com.instagram.barcelona", // Threads
        "com.zhiliaoapp.musically", // TikTok
        "com.ss.android.ugc.trill", // TikTok alt
        "com.google.android.youtube",
        "com.vimeo.android.videoapp",
        "com.facebook.katana",
        "com.facebook.lite",
        "com.facebook.orca", // Messenger
        "com.twitter.android",
        "com.snapchat.android",
        "com.reddit.frontpage",
        "tv.twitch.android.app",
        "com.linkedin.android",
        "com.pinterest",
        "com.discord",
        "com.whatsapp",
        "org.telegram.messenger",
        "com.tencent.mm", // WeChat
    )

    val KNOWN_MEDIA_PACKAGE_PREFIXES: Set<String> = setOf(
        "com.instagram.",
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.",
        "com.google.android.youtube",
        "com.facebook.",
        "com.twitter.",
        "com.snapchat.",
        "com.reddit.",
        "tv.twitch.",
        "com.linkedin.",
        "com.pinterest",
        "com.discord",
        "com.whatsapp",
        "org.telegram.",
    )

    fun isKnownMediaPackage(packageName: String): Boolean {
        val normalized = packageName.lowercase()
        if (normalized in KNOWN_MEDIA_PACKAGES_EXACT) return true
        return KNOWN_MEDIA_PACKAGE_PREFIXES.any { normalized.startsWith(it) }
    }

    /**
     * Launchable system apps that should default to unrestricted (not known media).
     */
    fun unrestrictedSystemCandidates(apps: List<AppInfo>): List<AppInfo> =
        apps.filter { it.isSystemApp && !isKnownMediaPackage(it.packageName) }
            .sortedBy { it.label.lowercase() }
}
