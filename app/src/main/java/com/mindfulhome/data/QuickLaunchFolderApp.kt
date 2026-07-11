package com.mindfulhome.data

/**
 * An app inside a Quick Launch folder. [limitMinutes] null = unlimited (session allowlist);
 * non-null = timed launch with a background timer and expiry nudges.
 */
data class QuickLaunchFolderApp(
    val packageName: String,
    val limitMinutes: Int? = null,
) {
    val isUnlimited: Boolean get() = limitMinutes == null

    companion object {
        const val DEFAULT_LIMIT_MINUTES = 3

        fun unlimited(packageName: String) = QuickLaunchFolderApp(packageName, null)

        fun timed(packageName: String, limitMinutes: Int = DEFAULT_LIMIT_MINUTES) =
            QuickLaunchFolderApp(packageName, limitMinutes.coerceAtLeast(1))
    }
}

fun List<String>.toUnlimitedFolderApps(): List<QuickLaunchFolderApp> =
    map { QuickLaunchFolderApp.unlimited(it) }

fun mergeFolderApps(
    first: List<QuickLaunchFolderApp>,
    second: List<QuickLaunchFolderApp>,
): List<QuickLaunchFolderApp> {
    val merged = LinkedHashMap<String, QuickLaunchFolderApp>()
    for (app in first + second) {
        val pkg = app.packageName.trim()
        if (pkg.isNotEmpty()) merged.putIfAbsent(pkg, app.copy(packageName = pkg))
    }
    return merged.values.toList()
}
