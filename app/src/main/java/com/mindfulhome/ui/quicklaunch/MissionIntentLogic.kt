package com.mindfulhome.ui.quicklaunch

import com.mindfulhome.data.QuickLaunchSlot
import com.mindfulhome.model.AppInfo

/** Pure helpers extracted from [MissionIntentSection] for unit testing and CRAP reduction. */

fun formatIntentMinutes(minutes: Int): String = when {
    minutes < 60 -> "${minutes}m"
    minutes % 60 == 0 -> "${minutes / 60}h"
    else -> "${minutes / 60}h ${minutes % 60}m"
}

fun hasEmptyNamedIntentFolder(rawSlots: List<QuickLaunchSlot>): Boolean =
    rawSlots.any { slot ->
        slot is QuickLaunchSlot.Folder &&
            !slot.name.isNullOrBlank() &&
            slot.apps.isEmpty()
    }

fun mapIntentSlotsToUi(
    rawSlots: List<QuickLaunchSlot>,
    installedByPkg: Map<String, AppInfo>,
    shortcutAppsFor: (QuickLaunchSlot.Folder) -> List<AppInfo>,
): List<QuickLaunchSlotUi> =
    rawSlots.mapNotNull { slot ->
        when (slot) {
            is QuickLaunchSlot.Single -> null
            is QuickLaunchSlot.Folder -> {
                val apps = slot.apps.mapNotNull { installedByPkg[it.packageName] } +
                    shortcutAppsFor(slot)
                QuickLaunchSlotUi(
                    apps = apps,
                    folderName = slot.name?.takeIf { it.isNotBlank() } ?: "Unnamed",
                    folderSymbolIconName = slot.symbolIconName,
                    limitMinutesByPackage = slot.limitMinutesByPackage(),
                )
            }
        }
    }

/**
 * Reconciles an open intent folder with current slots.
 * Returns null when the folder should close (slot gone or collapsed to Single).
 */
fun reconcileOpenIntentFolder(
    open: QuickLaunchFolderOpen,
    rawSlots: List<QuickLaunchSlot>,
    installedByPkg: Map<String, AppInfo>,
    shortcutAppsFor: (QuickLaunchSlot.Folder) -> List<AppInfo>,
): QuickLaunchFolderOpen? {
    val idx = open.slotIndex
    if (idx !in rawSlots.indices) return null
    return when (val slot = rawSlots[idx]) {
        is QuickLaunchSlot.Single -> null
        is QuickLaunchSlot.Folder -> {
            val apps = slot.apps.mapNotNull { installedByPkg[it.packageName] } +
                shortcutAppsFor(slot)
            QuickLaunchFolderOpen(
                idx,
                apps,
                slot.name,
                slot.symbolIconName,
                slot.apps.associate { it.packageName to it.limitMinutes },
            )
        }
    }
}

fun buildResumeAuxTile(
    resumeSessionLabel: String?,
    resumeSessionMinutes: Int,
    onResumeSession: (() -> Unit)?,
): QuickLaunchAuxTile? {
    if (resumeSessionLabel == null || onResumeSession == null || resumeSessionMinutes <= 0) {
        return null
    }
    return QuickLaunchAuxTile(
        label = "Resume",
        subtitle = "$resumeSessionLabel (${formatIntentMinutes(resumeSessionMinutes)})",
        onClick = onResumeSession,
        contentDescription = "Resume $resumeSessionLabel",
    )
}

fun missingPackagesInSlots(
    rawSlots: List<QuickLaunchSlot>,
    installedPackages: Set<String>,
): List<String> {
    if (installedPackages.isEmpty()) return emptyList()
    return rawSlots.flatMap { it.flattenPackages() }.filter { it !in installedPackages }
}

fun trimmedNonEmptyName(raw: String): String? =
    raw.trim().takeIf { it.isNotEmpty() }

fun folderTitleForIntent(folder: QuickLaunchFolderOpen): String =
    folder.folderName?.takeIf { it.isNotBlank() } ?: "Unnamed intent"
