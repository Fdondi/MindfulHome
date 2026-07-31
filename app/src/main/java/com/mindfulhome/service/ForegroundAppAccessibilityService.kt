package com.mindfulhome.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import com.mindfulhome.model.TimerState
import com.mindfulhome.settings.SettingsManager

/**
 * Event-driven replacement for the Quick Launch foreground poll.
 *
 * The system delivers [AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED] the instant the
 * foreground window (app) changes and at no other time, so we can detect an app switch
 * with ~zero latency and zero idle wakeups — no 1 Hz UsageStats polling.
 *
 * We deliberately keep [android.R.attr.canRetrieveWindowContent] = false: we only read the
 * event's package name, never window content. See res/xml/accessibility_service_config.xml.
 */
class ForegroundAppAccessibilityService : AccessibilityService() {

    private var lastPackage: String = ""

    private var imePackages: Set<String> = emptySet()
    private var imePackagesFetchedAtMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Connected — event-driven foreground detection active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == packageName) return
        // Toasts and some popups also fire WINDOW_STATE_CHANGED but aren't real switches.
        if (event.className?.toString()?.contains("Toast", ignoreCase = true) == true) return
        // IME (keyboard) windows also fire WINDOW_STATE_CHANGED when they show/hide, but the
        // user is still inside the underlying app — never report a keyboard as the foreground
        // app (and don't touch lastPackage, so the real app stays current).
        if (isInputMethodPackage(pkg)) return
        if (pkg == lastPackage) return
        lastPackage = pkg

        // Only wake TimerService when Quick Launch is active or the pre-timer gate is up.
        // Normal timer-expiry (birds/notification) does not need foreground redirects.
        if (!SettingsManager.isQuickLaunchSessionActive(this) &&
            !TimerService.shouldYouBeHereGateActive
        ) {
            return
        }

        Log.d(TAG, "Foreground app changed -> $pkg")
        TimerService.notifyForegroundApp(this, pkg)
    }

    override fun onInterrupt() {}

    /** True if [pkg] provides an input method (any installed keyboard, not just the active one). */
    private fun isInputMethodPackage(pkg: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (imePackages.isEmpty() || now - imePackagesFetchedAtMs > IME_CACHE_TTL_MS) {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imePackages = imm.inputMethodList.mapTo(mutableSetOf()) { it.packageName }
            imePackagesFetchedAtMs = now
        }
        return pkg in imePackages
    }

    companion object {
        private const val TAG = "FgA11yService"
        private const val IME_CACHE_TTL_MS = 5 * 60_000L

        /** True if the user has enabled this accessibility service in system settings. */
        fun isEnabled(context: Context): Boolean {
            val expected = "${context.packageName}/${ForegroundAppAccessibilityService::class.java.name}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabledServices)
            for (component in splitter) {
                if (component.equals(expected, ignoreCase = true)) return true
            }
            return false
        }

        /** Intent to send the user to the system Accessibility settings to enable the service. */
        fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }
}
