package com.mindfulhome.data

/** A pinned launcher shortcut stored inside an intent folder. */
data class PinnedShortcut(
    val packageName: String,
    val id: String,
    val label: String? = null,
    /** Stored launch intent for legacy browser shortcuts and as a fallback when [id] is not in LauncherApps. */
    val intentUri: String? = null,
)
