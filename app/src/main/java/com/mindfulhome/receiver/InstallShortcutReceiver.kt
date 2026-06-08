package com.mindfulhome.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mindfulhome.PinShortcutActivity

/**
 * Legacy "Add to Home screen" path used by some browsers (e.g. Brave) instead of
 * [android.content.pm.action.CONFIRM_PIN_SHORTCUT]. Only delivered to the default launcher.
 */
class InstallShortcutReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_SHORTCUT) return

        val shortcutIntent = readShortcutIntent(intent) ?: run {
            Log.w(TAG, "INSTALL_SHORTCUT missing shortcut intent")
            return
        }
        val label = readShortcutName(intent).ifBlank { "Shortcut" }
        val ownerPackage = shortcutIntent.`package`
            ?: shortcutIntent.component?.packageName
            ?: run {
                Log.w(TAG, "INSTALL_SHORTCUT could not resolve owner package")
                return
            }

        Log.d(TAG, "INSTALL_SHORTCUT from $ownerPackage label=$label")

        val pickerIntent = Intent(context, PinShortcutActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(PinShortcutActivity.EXTRA_LEGACY_INTENT_URI, shortcutIntent.toUri(Intent.URI_INTENT_SCHEME))
            putExtra(PinShortcutActivity.EXTRA_LEGACY_LABEL, label)
            putExtra(PinShortcutActivity.EXTRA_LEGACY_PACKAGE, ownerPackage)
        }
        context.startActivity(pickerIntent)
    }

    private fun readShortcutIntent(intent: Intent): Intent? {
        intent.getParcelableExtra(Intent.EXTRA_SHORTCUT_INTENT, Intent::class.java)?.let { return it }
        @Suppress("DEPRECATION")
        return intent.getParcelableExtra(EXTRA_SHORTCUT_INTENT_LEGACY)
    }

    private fun readShortcutName(intent: Intent): String {
        return intent.getStringExtra(Intent.EXTRA_SHORTCUT_NAME)
            ?: intent.getStringExtra(EXTRA_SHORTCUT_NAME_LEGACY)
            .orEmpty()
    }

    private companion object {
        private const val TAG = "InstallShortcutReceiver"
        private const val ACTION_INSTALL_SHORTCUT = "com.android.launcher.action.INSTALL_SHORTCUT"
        private const val EXTRA_SHORTCUT_INTENT_LEGACY = "android.intent.extra.shortcut.INTENT"
        private const val EXTRA_SHORTCUT_NAME_LEGACY = "android.intent.extra.shortcut.NAME"
    }
}
