package com.mindfulhome

import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.mindfulhome.data.AppRepository
import com.mindfulhome.data.PinnedShortcut
import com.mindfulhome.data.QuickLaunchSlot
import com.mindfulhome.ui.shortcut.AddToIntentFolderDialog
import com.mindfulhome.ui.shortcut.drawableToPainter
import com.mindfulhome.ui.shortcut.intentFolderOptions
import com.mindfulhome.ui.theme.MindfulHomeTheme
import com.mindfulhome.util.PackageManagerHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PinShortcutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "onCreate action=${intent.action} extras=${intent.extras?.keySet()?.joinToString()}")

        val launcherApps = getSystemService(LauncherApps::class.java)
        val pinRequest = launcherApps?.getPinItemRequest(intent)
        if (pinRequest != null && pinRequest.isValid &&
            pinRequest.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT
        ) {
            val shortcutInfo = pinRequest.shortcutInfo
            if (shortcutInfo == null) {
                Log.w(TAG, "Pin request valid but ShortcutInfo is null")
                finish()
                return
            }
            showFolderPicker(
                label = shortcutLabel(shortcutInfo),
                iconDrawable = launcherApps.getShortcutIconDrawable(shortcutInfo, 0),
                pinned = pinnedShortcutFrom(shortcutInfo, shortcutLabel(shortcutInfo)),
                onComplete = { shortcut, slotIndex, newFolderName, toastFolderName ->
                    completeModernPin(pinRequest, shortcut, slotIndex, newFolderName, toastFolderName)
                },
            )
            return
        }

        if (pinRequest != null && pinRequest.isValid &&
            pinRequest.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT
        ) {
            Toast.makeText(this, "Widget pinning is not supported yet.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val legacyIntentUri = intent.getStringExtra(EXTRA_LEGACY_INTENT_URI)
        val legacyLabel = intent.getStringExtra(EXTRA_LEGACY_LABEL)
        val legacyPackage = intent.getStringExtra(EXTRA_LEGACY_PACKAGE)
        if (legacyIntentUri != null && legacyPackage != null) {
            val label = legacyLabel?.takeIf { it.isNotBlank() } ?: legacyPackage
            val syntheticId = legacyShortcutId(legacyIntentUri)
            val pinned = PinnedShortcut(
                packageName = legacyPackage,
                id = syntheticId,
                label = label,
                intentUri = legacyIntentUri,
            )
            Log.d(TAG, "Legacy INSTALL_SHORTCUT pkg=$legacyPackage id=$syntheticId")
            showFolderPicker(
                label = label,
                iconDrawable = PackageManagerHelper.getAppIcon(this, legacyPackage),
                pinned = pinned,
                onComplete = { shortcut, slotIndex, newFolderName, toastFolderName ->
                    completeLegacyPin(shortcut, slotIndex, newFolderName, toastFolderName)
                },
            )
            return
        }

        Log.w(TAG, "No pin request or legacy shortcut data in intent")
        finish()
    }

    private fun showFolderPicker(
        label: String,
        iconDrawable: android.graphics.drawable.Drawable?,
        pinned: PinnedShortcut,
        onComplete: (
            shortcut: PinnedShortcut,
            existingSlotIndex: Int?,
            newFolderName: String?,
            toastFolderName: String?,
        ) -> Unit,
    ) {
        val repository = AppRepository((application as MindfulHomeApp).database)

        setContent {
            MindfulHomeTheme {
                var folders by remember { mutableStateOf<List<QuickLaunchSlot>>(emptyList()) }

                LaunchedEffect(Unit) {
                    val installed = PackageManagerHelper.getInstalledApps(this@PinShortcutActivity)
                    repository.ensureIntentQuickLaunchInitialized(installed.map { it.packageName }.toSet())
                    folders = repository.quickLaunchSlots().first()
                }

                AddToIntentFolderDialog(
                    shortcutLabel = label,
                    shortcutIcon = drawableToPainter(iconDrawable),
                    existingFolders = intentFolderOptions(folders),
                    onAddToExistingFolder = { slotIndex ->
                        val folderName = intentFolderOptions(folders)
                            .firstOrNull { it.slotIndex == slotIndex }?.name
                        onComplete(pinned, slotIndex, null, folderName)
                    },
                    onAddToNewFolder = { folderName ->
                        onComplete(pinned, null, folderName, folderName)
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }

    private fun completeModernPin(
        pinRequest: LauncherApps.PinItemRequest,
        shortcut: PinnedShortcut,
        existingSlotIndex: Int?,
        newFolderName: String?,
        toastFolderName: String?,
    ) {
        if (shortcut.packageName.isBlank() || shortcut.id.isBlank()) {
            Toast.makeText(this, "Shortcut is missing required information.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (!pinRequest.isValid) {
            Toast.makeText(this, "Shortcut request expired. Try again from the app.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val accepted = pinRequest.accept()
        if (!accepted) {
            Log.w(TAG, "PinItemRequest.accept() failed for ${shortcut.packageName}/${shortcut.id}")
            Toast.makeText(this, "Could not pin shortcut. Try again.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        saveShortcutToFolder(shortcut, existingSlotIndex, newFolderName, toastFolderName)
    }

    private fun completeLegacyPin(
        shortcut: PinnedShortcut,
        existingSlotIndex: Int?,
        newFolderName: String?,
        toastFolderName: String?,
    ) {
        if (shortcut.packageName.isBlank() || shortcut.id.isBlank() || shortcut.intentUri.isNullOrBlank()) {
            Toast.makeText(this, "Shortcut is missing required information.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        saveShortcutToFolder(shortcut, existingSlotIndex, newFolderName, toastFolderName)
    }

    private fun saveShortcutToFolder(
        shortcut: PinnedShortcut,
        existingSlotIndex: Int?,
        newFolderName: String?,
        toastFolderName: String?,
    ) {
        val repository = AppRepository((application as MindfulHomeApp).database)
        lifecycleScope.launch {
            val saved = when {
                existingSlotIndex != null ->
                    repository.addShortcutToQuickLaunchFolderAt(existingSlotIndex, shortcut)
                newFolderName != null ->
                    repository.addShortcutToNewIntentFolder(newFolderName, shortcut)
                else -> false
            }
            if (!saved) {
                Log.w(TAG, "Shortcut not saved to folder ${shortcut.packageName}/${shortcut.id}")
                Toast.makeText(
                    this@PinShortcutActivity,
                    "Could not save shortcut to folder.",
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                Toast.makeText(
                    this@PinShortcutActivity,
                    "Added to ${toastFolderName ?: "intent folder"}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            finish()
        }
    }

    private fun shortcutLabel(shortcutInfo: ShortcutInfo): String {
        return shortcutInfo.shortLabel?.toString()?.takeIf { it.isNotBlank() }
            ?: shortcutInfo.longLabel?.toString()?.takeIf { it.isNotBlank() }
            ?: shortcutInfo.`package`.ifBlank { "Shortcut" }
    }

    private fun pinnedShortcutFrom(shortcutInfo: ShortcutInfo, label: String): PinnedShortcut {
        val intentUri = shortcutInfo.intent?.toUri(Intent.URI_INTENT_SCHEME)
        return PinnedShortcut(
            packageName = shortcutInfo.`package`,
            id = shortcutInfo.id,
            label = label,
            intentUri = intentUri,
        )
    }

    companion object {
        private const val TAG = "PinShortcutActivity"

        const val EXTRA_LEGACY_INTENT_URI = "com.mindfulhome.extra.LEGACY_INTENT_URI"
        const val EXTRA_LEGACY_LABEL = "com.mindfulhome.extra.LEGACY_LABEL"
        const val EXTRA_LEGACY_PACKAGE = "com.mindfulhome.extra.LEGACY_PACKAGE"

        fun legacyShortcutId(intentUri: String): String = "legacy-${intentUri.hashCode()}"
    }
}
