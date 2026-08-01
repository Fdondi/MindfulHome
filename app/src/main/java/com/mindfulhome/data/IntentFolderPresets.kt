package com.mindfulhome.data

/** Preset intent folders for the default-page mission grid. */
data class IntentFolderPreset(
    val name: String,
    /** Full package names or suffixes matched with [packageMatchesPreset]. */
    val packagePatterns: List<String>,
    val symbolIconName: String? = null,
)

object IntentFolderPresets {
    const val MIGRATION_KV_KEY = "intent_folders_v1"

    val all: List<IntentFolderPreset> = listOf(
        IntentFolderPreset(
            name = "Search",
            symbolIconName = "search",
            packagePatterns = listOf(
                "com.android.chrome",
                "org.mozilla.firefox",
                "com.microsoft.emmx",
                "com.brave.browser",
                "com.opera.browser",
                "com.opera.mini.native",
                "com.sec.android.app.sbrowser",
                "com.duckduckgo.mobile.android",
                "com.vivaldi.browser",
            ),
        ),
        IntentFolderPreset(
            name = "Reflect",
            symbolIconName = "psychology",
            packagePatterns = listOf(
                "com.openai.chatgpt",
                "com.google.android.apps.bard",
                "com.anthropic.claude",
                "com.microsoft.copilot",
                "ai.perplexity.app.android",
                "com.deepseek.chat",
            ),
        ),
        IntentFolderPreset(
            name = "Travel",
            symbolIconName = "flight_takeoff",
            packagePatterns = listOf(
                "com.google.android.apps.maps",
                "com.waze",
                "com.google.android.apps.walletnfcrel",
                "com.ubercab",
                "com.lyft.android",
                "com.google.android.apps.nbu.paisa.user",
                "com.citymapper.app.release",
            ),
        ),
        IntentFolderPreset(
            name = "Learn",
            symbolIconName = "menu_book",
            packagePatterns = listOf(
                "com.duolingo",
                "org.coursera.android",
                "com.amazon.kindle",
                "com.linkedin.android.learning",
                "org.khanacademy.android",
            ),
        ),
        IntentFolderPreset(
            name = "Connect",
            symbolIconName = "chat",
            packagePatterns = listOf(
                "com.google.android.apps.messaging",
                "com.whatsapp",
                "org.telegram.messenger",
                "org.thoughtcrime.securesms",
                "com.facebook.orca",
                "com.discord",
                "com.Slack",
                "com.instagram.android",
            ),
        ),
        IntentFolderPreset(
            name = "Organize",
            symbolIconName = "event",
            packagePatterns = listOf(
                "com.google.android.calendar",
                "com.google.android.keep",
                "com.google.android.gm",
                "com.microsoft.office.outlook",
                "com.samsung.android.app.notes",
                "com.todoist",
                "com.notion.id",
            ),
        ),
        IntentFolderPreset(
            name = "Snap",
            symbolIconName = "photo_camera",
            packagePatterns = listOf(
                "com.snapchat.android",
                "com.android.camera",
                "com.android.camera2",
                "com.google.android.GoogleCamera",
                "com.sec.android.app.camera",
            ),
        ),
        IntentFolderPreset(
            name = "Util",
            symbolIconName = "apps",
            packagePatterns = emptyList(),
        ),
    )

    fun packageMatchesPreset(packageName: String, preset: IntentFolderPreset): Boolean {
        if (preset.packagePatterns.isEmpty()) return false
        return preset.packagePatterns.any { pattern ->
            packageName == pattern || packageName.endsWith(".$pattern") || packageName.endsWith(pattern)
        }
    }

    fun presetForPackage(packageName: String): IntentFolderPreset? =
        all.firstOrNull { it.name != "Util" && packageMatchesPreset(packageName, it) }

    fun buildInitialSlots(installedPackages: Set<String>): List<QuickLaunchSlot.Folder> =
        all.map { preset ->
            val apps = installedPackages.filter { packageMatchesPreset(it, preset) }.distinct()
            QuickLaunchSlot.Folder(preset.name, apps.toUnlimitedFolderApps(), preset.symbolIconName)
        }

    fun applyMissingPresetSymbols(slots: List<QuickLaunchSlot>): List<QuickLaunchSlot> =
        slots.map { slot ->
            if (slot !is QuickLaunchSlot.Folder || !slot.symbolIconName.isNullOrBlank()) {
                slot
            } else {
                val preset = all.firstOrNull { it.name == slot.name }
                if (preset?.symbolIconName != null) {
                    slot.copy(symbolIconName = preset.symbolIconName)
                } else {
                    slot
                }
            }
        }

    fun migrateLegacySlots(
        slots: List<QuickLaunchSlot>,
        installedPackages: Set<String>,
    ): List<QuickLaunchSlot.Folder> {
        val presetSlots = buildInitialSlots(installedPackages).associateBy { it.name!! }.toMutableMap()
        val utilApps = mutableListOf<String>()
        slots.forEach { absorbLegacySlot(it, presetSlots, utilApps) }
        if (utilApps.isNotEmpty()) {
            val util = presetSlots["Util"]!!
            presetSlots["Util"] = util.copy(apps = mergeFolderApps(util.apps, utilApps.toUnlimitedFolderApps()))
        }
        return all.mapNotNull { preset -> presetSlots[preset.name] }
    }

    private fun absorbLegacySlot(
        slot: QuickLaunchSlot,
        presetSlots: MutableMap<String, QuickLaunchSlot.Folder>,
        utilApps: MutableList<String>,
    ) {
        when (slot) {
            is QuickLaunchSlot.Folder -> absorbLegacyFolder(slot, presetSlots, utilApps)
            is QuickLaunchSlot.Single -> assignPackage(slot.packageName, presetSlots, utilApps)
        }
    }

    private fun absorbLegacyFolder(
        slot: QuickLaunchSlot.Folder,
        presetSlots: MutableMap<String, QuickLaunchSlot.Folder>,
        utilApps: MutableList<String>,
    ) {
        val name = slot.name?.trim()?.takeIf { it.isNotEmpty() }
        if (name == null) {
            slot.flattenPackages().forEach { pkg -> assignPackage(pkg, presetSlots, utilApps) }
            return
        }
        mergeNamedLegacyFolder(name, slot, presetSlots)
    }

    private fun mergeNamedLegacyFolder(
        name: String,
        slot: QuickLaunchSlot.Folder,
        presetSlots: MutableMap<String, QuickLaunchSlot.Folder>,
    ) {
        val preset = all.firstOrNull { it.name == name }
        val existing = presetSlots[name]
        if (existing != null) {
            presetSlots[name] = existing.copy(
                apps = mergeFolderApps(existing.apps, slot.apps),
                symbolIconName = existing.symbolIconName
                    ?: slot.symbolIconName
                    ?: preset?.symbolIconName,
            )
        } else {
            presetSlots[name] = QuickLaunchSlot.Folder(
                name,
                mergeFolderApps(emptyList(), slot.apps),
                slot.symbolIconName ?: preset?.symbolIconName,
            )
        }
    }

    private fun assignPackage(
        packageName: String,
        presetSlots: MutableMap<String, QuickLaunchSlot.Folder>,
        utilApps: MutableList<String>,
    ) {
        if (packageName.isBlank()) return
        val preset = presetForPackage(packageName)
        if (preset == null) {
            utilApps.add(packageName)
            return
        }
        val existing = presetSlots[preset.name]!!
        if (packageName in existing.packageNames()) return
        presetSlots[preset.name] = existing.copy(
            apps = existing.apps + QuickLaunchFolderApp.unlimited(packageName),
        )
    }
}
