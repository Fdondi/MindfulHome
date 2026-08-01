package com.mindfulhome.service

/** Pure helpers for [ForegroundAppAccessibilityService]. */
object ForegroundA11yLogic {

    fun shouldIgnoreWindowStateEvent(
        eventType: Int,
        windowStateChangedType: Int,
        packageName: String,
        ownPackageName: String,
        className: String?,
        isImePackage: Boolean,
        lastPackage: String,
    ): Boolean {
        if (eventType != windowStateChangedType) return true
        if (packageName.isBlank() || packageName == ownPackageName) return true
        if (className?.contains("Toast", ignoreCase = true) == true) return true
        if (isImePackage) return true
        if (packageName == lastPackage) return true
        return false
    }

    fun shouldNotifyTimerService(
        quickLaunchActive: Boolean,
        shouldYouBeHereGateActive: Boolean,
    ): Boolean = quickLaunchActive || shouldYouBeHereGateActive
}
