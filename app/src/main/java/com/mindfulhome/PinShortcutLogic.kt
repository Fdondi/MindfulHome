package com.mindfulhome

sealed class PinShortcutPath {
    data class ModernShortcut(
        val hasShortcutInfo: Boolean,
    ) : PinShortcutPath()

    data object UnsupportedPinType : PinShortcutPath()

    data class LegacyShortcut(
        val intentUri: String,
        val label: String?,
        val packageName: String,
    ) : PinShortcutPath()

    data object None : PinShortcutPath()
}

fun classifyPinShortcutIntent(
    pinRequestValid: Boolean,
    pinRequestType: Int?,
    shortcutRequestType: Int,
    hasShortcutInfo: Boolean,
    legacyIntentUri: String?,
    legacyLabel: String?,
    legacyPackage: String?,
): PinShortcutPath {
    if (pinRequestValid && pinRequestType == shortcutRequestType) {
        return PinShortcutPath.ModernShortcut(hasShortcutInfo = hasShortcutInfo)
    }
    if (pinRequestValid && pinRequestType != null && pinRequestType != shortcutRequestType) {
        return PinShortcutPath.UnsupportedPinType
    }
    if (legacyIntentUri != null && legacyPackage != null) {
        return PinShortcutPath.LegacyShortcut(
            intentUri = legacyIntentUri,
            label = legacyLabel,
            packageName = legacyPackage,
        )
    }
    return PinShortcutPath.None
}

fun resolveLegacyShortcutLabel(legacyLabel: String?, legacyPackage: String): String =
    legacyLabel?.takeIf { it.isNotBlank() } ?: legacyPackage
