package com.mindfulhome.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.max

class AppRepository(private val database: AppDatabase) {
    private companion object {
        const val DEFAULT_HIDE_THRESHOLD = -2
    }

    private val karmaDao = database.appKarmaDao()
    private val sessionDao = database.usageSessionDao()
    private val layoutDao = database.homeLayoutDao()
    private val intentDao = database.appIntentDao()
    private val todoDao = database.todoDao()
    private val appKvDao = database.appKvDao()
    private val dailyLogSummaryDao = database.dailyLogSummaryDao()

    // Karma
    fun allKarma(): Flow<List<AppKarma>> = karmaDao.getAllKarma()
    fun hiddenApps(): Flow<List<AppKarma>> = karmaDao.getHiddenApps()

    suspend fun getKarma(packageName: String): AppKarma {
        if (packageName.isBlank()) return AppKarma(packageName = packageName)
        return karmaDao.getKarma(packageName) ?: AppKarma(packageName = packageName).also {
            karmaDao.upsert(it)
        }
    }

    suspend fun adjustKarma(
        packageName: String,
        delta: Int,
        hideThreshold: Int = DEFAULT_HIDE_THRESHOLD
    ) {
        mutateKarma(packageName, hideThreshold) { current ->
            current.copy(karmaScore = current.karmaScore + delta)
        }
    }

    suspend fun recordAppOpened(packageName: String) {
        val current = getKarma(packageName)
        karmaDao.upsert(
            current.copy(
                totalOpens = current.totalOpens + 1,
                lastOpenedTimestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun recordClosedOnTime(packageName: String, hideThreshold: Int) {
        mutateKarma(packageName, hideThreshold, allowWhenOptedOut = true) { current ->
            val recovered = if (current.isOptedOut) {
                current.karmaScore
            } else {
                (current.karmaScore + 1).coerceAtMost(0)
            }
            current.copy(
                karmaScore = recovered,
                closedOnTimeCount = current.closedOnTimeCount + 1
            )
        }
    }

    suspend fun recordOverrun(packageName: String) {
        val current = getKarma(packageName)
        karmaDao.upsert(
            current.copy(
                totalOverruns = current.totalOverruns + 1
            )
        )
    }

    suspend fun forgiveApp(packageName: String) {
        mutateKarma(packageName, allowWhenOptedOut = true) { current ->
            current.copy(karmaScore = 0)
        }
    }

    suspend fun setKarmaScore(
        packageName: String,
        karmaScore: Int,
        hideThreshold: Int = DEFAULT_HIDE_THRESHOLD,
    ) {
        mutateKarma(packageName, hideThreshold, allowWhenOptedOut = true) { current ->
            current.copy(karmaScore = karmaScore)
        }
    }

    suspend fun setOptedOut(packageName: String, optedOut: Boolean) {
        val current = getKarma(packageName) // ensure the row exists before updating
        val updated = if (optedOut) {
            current.copy(isOptedOut = true, karmaScore = 0)
        } else {
            current.copy(isOptedOut = false)
        }
        upsertWithDerivedHidden(updated)
    }

    suspend fun updateAppNote(packageName: String, note: String?) {
        val current = getKarma(packageName)
        val normalized = note?.trim()?.takeIf { it.isNotBlank() }
        upsertWithDerivedHidden(current.copy(appNote = normalized))
    }

    suspend fun dailyKarmaRecovery(hideThreshold: Int) {
        val underwaterApps = karmaDao.getUnderwaterAppsForRecovery()
        underwaterApps.forEach { appKarma ->
            val recovered = (appKarma.karmaScore + 1).coerceAtMost(0)
            upsertWithDerivedHidden(appKarma.copy(karmaScore = recovered), hideThreshold)
        }
    }

    private suspend fun mutateKarma(
        packageName: String,
        hideThreshold: Int = DEFAULT_HIDE_THRESHOLD,
        allowWhenOptedOut: Boolean = false,
        update: (AppKarma) -> AppKarma
    ) {
        val current = getKarma(packageName)
        if (current.isOptedOut && !allowWhenOptedOut) return
        val updated = update(current)
        upsertWithDerivedHidden(updated, hideThreshold)
    }

    private suspend fun upsertWithDerivedHidden(
        karma: AppKarma,
        hideThreshold: Int = DEFAULT_HIDE_THRESHOLD
    ) {
        val shouldHide = !karma.isOptedOut && karma.karmaScore <= hideThreshold
        karmaDao.upsert(karma.copy(isHidden = shouldHide))
    }

    // Sessions
    suspend fun startSession(packageName: String, timerDurationMs: Long): Long {
        return sessionDao.insert(
            UsageSession(
                packageName = packageName,
                startTimestamp = System.currentTimeMillis(),
                timerDurationMs = timerDurationMs
            )
        )
    }

    suspend fun endSession(sessionId: Long, closedOnTime: Boolean, overrunMs: Long = 0, karmaChange: Int = 0) {
        val session = sessionDao.getSession(sessionId) ?: return
        sessionDao.update(
            session.copy(
                endTimestamp = System.currentTimeMillis(),
                closedOnTime = closedOnTime,
                overrunMs = overrunMs,
                karmaChange = karmaChange
            )
        )
    }

    suspend fun getRecentSessions(packageName: String): List<UsageSession> {
        return sessionDao.getRecentSessions(packageName)
    }

    // Daily log summaries
    suspend fun getLatestDailySummaries(limit: Int = 5): List<DailyLogSummary> {
        val safeLimit = limit.coerceIn(1, 30)
        return dailyLogSummaryDao.getLatest(safeLimit)
    }

    // Layout
    fun homeLayout(): Flow<List<HomeLayoutItem>> = layoutDao.getLayout()
    fun dockedApps(): Flow<List<HomeLayoutItem>> = layoutDao.getDockedApps()

    suspend fun setDocked(packageName: String, dockPosition: Int) {
        val existing = layoutDao.getByPackageName(packageName)
        if (existing != null) {
            layoutDao.upsert(existing.copy(isDocked = true, dockPosition = dockPosition))
        } else {
            layoutDao.upsert(
                HomeLayoutItem(
                    packageName = packageName,
                    isDocked = true,
                    dockPosition = dockPosition
                )
            )
        }
    }

    suspend fun removeDocked(packageName: String) {
        val existing = layoutDao.getByPackageName(packageName)
        if (existing != null) {
            layoutDao.upsert(existing.copy(isDocked = false))
        }
    }

    suspend fun dockedCount(): Int = layoutDao.dockedCount()

    suspend fun updateGridPositions(items: List<HomeLayoutItem>) {
        layoutDao.upsertAll(items)
    }

    // Intents (declared reasons for opening apps)
    fun allIntents(): Flow<List<AppIntent>> = intentDao.getAllIntents()

    suspend fun recordIntent(packageName: String, text: String) {
        intentDao.insert(AppIntent(packageName = packageName, intentText = text))
    }

    // Favorites (home strip): same JSON model as QuickLaunch — [FavoritesKv.KEY] in app_kv.
    fun favoritesSlots(): Flow<List<QuickLaunchSlot>> =
        appKvDao.observeValue(FavoritesKv.KEY).map { raw ->
            normalizeQuickLaunchSlots(QuickLaunchJson.decode(raw))
        }.distinctUntilChanged()

    suspend fun addToFavorites(packageName: String) {
        if (packageName.isBlank()) return
        persistFavorites(favoritesSnapshot() + QuickLaunchSlot.Single(packageName))
    }

    /** Merge [packageName] into the slot at [uiIndex] (folder if the tile already holds apps). */
    suspend fun mergePackageIntoFavoritesAt(uiIndex: Int, packageName: String) {
        if (packageName.isBlank()) return
        val slots = favoritesSnapshot().toMutableList()
        if (uiIndex !in slots.indices) return
        val existing = slots[uiIndex]
        val mergedPkgs = (existing.flattenPackages() + packageName).distinct()
        val merged: QuickLaunchSlot = if (mergedPkgs.size == 1) {
            QuickLaunchSlot.Single(mergedPkgs[0])
        } else {
            val name = (existing as? QuickLaunchSlot.Folder)?.name?.takeIf { !it.isNullOrBlank() }
            val sym = (existing as? QuickLaunchSlot.Folder)?.symbolIconName?.takeIf { !it.isNullOrBlank() }
            QuickLaunchSlot.Folder(name, mergedPkgs.toUnlimitedFolderApps(), sym)
        }
        slots[uiIndex] = merged
        persistFavorites(slots)
    }

    suspend fun removeFromFavorites(packageName: String) {
        persistFavorites(removePackageFromSlots(favoritesSnapshot(), packageName))
    }

    /** Remove [packageName] only from the Favorites slot at [uiIndex] (other folders untouched). */
    suspend fun removePackageFromFavoritesAt(uiIndex: Int, packageName: String) {
        if (packageName.isBlank()) return
        val slots = favoritesSnapshot().toMutableList()
        if (uiIndex !in slots.indices) return
        val updated = removePackageFromSlots(listOf(slots[uiIndex]), packageName)
        if (updated.isEmpty()) {
            slots.removeAt(uiIndex)
        } else {
            slots[uiIndex] = updated.single()
        }
        persistFavorites(slots)
    }

    suspend fun removeFavoritesSlotAt(uiIndex: Int) {
        val slots = favoritesSnapshot().toMutableList()
        if (uiIndex !in slots.indices) return
        slots.removeAt(uiIndex)
        persistFavorites(slots)
    }

    suspend fun moveFavoritesSlot(fromUiIndex: Int, toUiIndex: Int) {
        if (fromUiIndex == toUiIndex) return
        val m = favoritesSnapshot().toMutableList()
        if (fromUiIndex !in m.indices || toUiIndex !in m.indices) return
        val moved = m.removeAt(fromUiIndex)
        m.add(toUiIndex, moved)
        persistFavorites(m)
    }

    suspend fun mergeFavoritesSlots(fromUiIndex: Int, intoUiIndex: Int) {
        mergeSlotsMutable(favoritesSnapshot().toMutableList(), fromUiIndex, intoUiIndex)?.let { persistFavorites(it) }
    }

    suspend fun extractFavoritesAppToOwnSlot(packageName: String) {
        val m = favoritesSnapshot().toMutableList()
        extractFromFolderSlot(m, packageName)?.let { persistFavorites(it) }
    }

    suspend fun setFavoritesFolderName(anchorPackageName: String, name: String?) {
        val normalized = name?.trim()?.takeIf { it.isNotEmpty() }
        val slots = favoritesSnapshot().map { slot ->
            if (slot is QuickLaunchSlot.Folder && anchorPackageName in slot.packageNames()) {
                slot.copy(name = normalized)
            } else {
                slot
            }
        }
        persistFavorites(slots)
    }

    suspend fun setFavoritesFolderSymbolIcon(anchorPackageName: String, symbolIconName: String?) {
        val normalized = symbolIconName?.trim()?.takeIf { it.isNotEmpty() }
        val slots = favoritesSnapshot().map { slot ->
            if (slot is QuickLaunchSlot.Folder && anchorPackageName in slot.packageNames()) {
                slot.copy(symbolIconName = normalized)
            } else {
                slot
            }
        }
        persistFavorites(slots)
    }

    private suspend fun favoritesSnapshot(): List<QuickLaunchSlot> {
        val raw = appKvDao.getValue(FavoritesKv.KEY)
        return normalizeQuickLaunchSlots(QuickLaunchJson.decode(raw))
    }

    private suspend fun persistFavorites(slots: List<QuickLaunchSlot>) =
        persistSlotKey(FavoritesKv.KEY, slots)

    // QuickLaunch (default page): ordered JSON — [QuickLaunchJson.KV_KEY].
    fun quickLaunchSlots(): Flow<List<QuickLaunchSlot>> =
        appKvDao.observeValue(QuickLaunchJson.KV_KEY).map { raw ->
            normalizeIntentQuickLaunchSlots(QuickLaunchJson.decodeIntentSlots(raw))
        }.distinctUntilChanged()

    suspend fun ensureIntentQuickLaunchInitialized(installedPackages: Set<String>) {
        if (appKvDao.getValue(IntentFolderPresets.MIGRATION_KV_KEY) != "done") {
            val raw = appKvDao.getValue(QuickLaunchJson.KV_KEY)
            val decoded = QuickLaunchJson.decodeIntentSlots(raw)
            val slots = if (decoded.isEmpty()) {
                IntentFolderPresets.buildInitialSlots(installedPackages)
            } else {
                IntentFolderPresets.migrateLegacySlots(decoded, installedPackages)
            }
            persistQuickLaunch(slots)
            appKvDao.upsert(AppKv(IntentFolderPresets.MIGRATION_KV_KEY, "done"))
        }
        val current = quickLaunchSnapshot()
        val withSymbols = IntentFolderPresets.applyMissingPresetSymbols(current)
        if (withSymbols != current) {
            persistQuickLaunch(withSymbols)
        }
    }

    suspend fun addShortcutToQuickLaunchFolderAt(uiIndex: Int, shortcut: PinnedShortcut): Boolean {
        if (shortcut.packageName.isBlank() || shortcut.id.isBlank()) return false
        val slots = quickLaunchSnapshot().toMutableList()
        if (uiIndex !in slots.indices) return false
        val slot = slots[uiIndex] as? QuickLaunchSlot.Folder ?: return false
        return mergeShortcutIntoFolder(slots, uiIndex, slot, shortcut)
    }

    private suspend fun mergeShortcutIntoFolder(
        slots: MutableList<QuickLaunchSlot>,
        uiIndex: Int,
        slot: QuickLaunchSlot.Folder,
        shortcut: PinnedShortcut,
    ): Boolean {
        if (slot.shortcuts.any { it.packageName == shortcut.packageName && it.id == shortcut.id }) {
            return true
        }
        slots[uiIndex] = slot.copy(shortcuts = slot.shortcuts + shortcut)
        persistQuickLaunch(slots)
        return true
    }

    suspend fun addShortcutToNewIntentFolder(name: String, shortcut: PinnedShortcut): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || shortcut.packageName.isBlank() || shortcut.id.isBlank()) return false
        persistQuickLaunch(
            quickLaunchSnapshot() + QuickLaunchSlot.Folder(
                name = trimmed,
                apps = emptyList(),
                shortcuts = listOf(shortcut),
            ),
        )
        return true
    }

    /** Returns the stored shortcut (with [PinnedShortcut.intentUri]) for a synthetic launch key. */
    suspend fun findPinnedShortcutByLaunchKey(launchKey: String): PinnedShortcut? {
        val parsed = com.mindfulhome.util.QuickLaunchAppRef.parseShortcut(launchKey) ?: return null
        for (slot in quickLaunchSnapshot()) {
            if (slot is QuickLaunchSlot.Folder) {
                slot.shortcuts.firstOrNull {
                    it.packageName == parsed.packageName && it.id == parsed.id
                }?.let { return it }
            }
        }
        return parsed
    }

    suspend fun addIntentFolder(name: String, apps: List<String> = emptyList()) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val cleanedApps = apps.filter { it.isNotBlank() }.distinct().toUnlimitedFolderApps()
        persistQuickLaunch(
            quickLaunchSnapshot() + QuickLaunchSlot.Folder(trimmed, cleanedApps),
        )
    }

    suspend fun addToQuickLaunch(packageName: String) {
        if (packageName.isBlank()) return
        persistQuickLaunch(
            quickLaunchSnapshot() + QuickLaunchSlot.Folder(
                null,
                listOf(QuickLaunchFolderApp.unlimited(packageName)),
            ),
        )
    }

    /** Merge [packageName] into the slot at [uiIndex]. [limitMinutes] null = unlimited allowlist. */
    suspend fun mergePackageIntoQuickLaunchAt(
        uiIndex: Int,
        packageName: String,
        limitMinutes: Int? = QuickLaunchFolderApp.DEFAULT_LIMIT_MINUTES,
    ) {
        if (packageName.isBlank()) return
        val slots = quickLaunchSnapshot().toMutableList()
        if (uiIndex !in slots.indices) return
        val existing = slots[uiIndex]
        val existingApps = when (existing) {
            is QuickLaunchSlot.Folder -> existing.apps
            else -> existing.flattenPackages().map { QuickLaunchFolderApp.unlimited(it) }
        }
        val newApp = if (limitMinutes == null) {
            QuickLaunchFolderApp.unlimited(packageName)
        } else {
            QuickLaunchFolderApp.timed(packageName, limitMinutes)
        }
        val mergedApps = mergeFolderApps(existingApps, listOf(newApp))
        val folder = existing as? QuickLaunchSlot.Folder
        val name = folder?.name?.takeIf { !it.isNullOrBlank() }
        val sym = folder?.symbolIconName?.takeIf { !it.isNullOrBlank() }
        val shortcuts = folder?.shortcuts.orEmpty()
        slots[uiIndex] = QuickLaunchSlot.Folder(name, mergedApps, sym, shortcuts)
        persistQuickLaunch(slots)
    }

    suspend fun setQuickLaunchAppLimitAt(
        uiIndex: Int,
        packageName: String,
        limitMinutes: Int?,
    ) {
        if (packageName.isBlank()) return
        val slots = quickLaunchSnapshot().toMutableList()
        if (uiIndex !in slots.indices) return
        val slot = slots[uiIndex] as? QuickLaunchSlot.Folder ?: return
        if (packageName !in slot.packageNames()) return
        slots[uiIndex] = slot.copy(apps = remapFolderAppLimit(slot.apps, packageName, limitMinutes))
        persistQuickLaunch(slots)
    }

    private fun remapFolderAppLimit(
        apps: List<QuickLaunchFolderApp>,
        packageName: String,
        limitMinutes: Int?,
    ): List<QuickLaunchFolderApp> = apps.map { app ->
        if (app.packageName != packageName) app
        else if (limitMinutes == null) QuickLaunchFolderApp.unlimited(packageName)
        else QuickLaunchFolderApp.timed(packageName, limitMinutes)
    }

    suspend fun quickLaunchLimitMinutesFor(packageName: String): Int? {
        if (packageName.isBlank()) return null
        val owner = com.mindfulhome.util.QuickLaunchAppRef.ownerPackage(packageName)
        for (slot in quickLaunchSnapshot()) {
            if (slot is QuickLaunchSlot.Folder) {
                slot.limitMinutesFor(owner)?.let { return it }
            }
        }
        return null
    }

    suspend fun removeLaunchKeyFromQuickLaunch(launchKey: String) {
        val shortcut = com.mindfulhome.util.QuickLaunchAppRef.parseShortcut(launchKey)
        if (shortcut != null) {
            persistQuickLaunch(
                quickLaunchSnapshot().map { slot ->
                    when (slot) {
                        is QuickLaunchSlot.Folder -> slot.copy(
                            shortcuts = slot.shortcuts.filterNot {
                                it.packageName == shortcut.packageName && it.id == shortcut.id
                            },
                        )
                        else -> slot
                    }
                },
            )
        } else {
            removeFromQuickLaunch(launchKey)
        }
    }

    /**
     * Remove a launch key (plain package or shortcut) only from the Quick Launch slot at [uiIndex].
     * Other folders that also contain the same package stay unchanged.
     */
    suspend fun removeLaunchKeyFromQuickLaunchAt(uiIndex: Int, launchKey: String) {
        if (launchKey.isBlank()) return
        val slots = quickLaunchSnapshot().toMutableList()
        if (uiIndex !in slots.indices) return
        val shortcut = com.mindfulhome.util.QuickLaunchAppRef.parseShortcut(launchKey)
        if (shortcut != null) {
            removeShortcutFromQuickLaunchAt(slots, uiIndex, shortcut)
            return
        }
        val updated = removePackageFromIntentSlots(listOf(slots[uiIndex]), launchKey)
        if (updated.isEmpty()) {
            slots.removeAt(uiIndex)
        } else {
            slots[uiIndex] = updated.single()
        }
        persistQuickLaunch(slots)
    }

    private suspend fun removeShortcutFromQuickLaunchAt(
        slots: MutableList<QuickLaunchSlot>,
        uiIndex: Int,
        shortcut: PinnedShortcut,
    ) {
        val slot = slots[uiIndex]
        if (slot !is QuickLaunchSlot.Folder) return
        slots[uiIndex] = slot.copy(
            shortcuts = slot.shortcuts.filterNot {
                it.packageName == shortcut.packageName && it.id == shortcut.id
            },
        )
        persistQuickLaunch(slots)
    }

    suspend fun removeFromQuickLaunch(packageName: String) {
        persistQuickLaunch(removePackageFromIntentSlots(quickLaunchSnapshot(), packageName))
    }

    /** All packages present in Quick Launch slots (any limit), for expired Recents allowlisting. */
    suspend fun allQuickLaunchPackages(): Set<String> =
        quickLaunchSnapshot().flatMap { it.flattenPackages() }.toSet()

    suspend fun swapQuickLaunchSlotsAt(uiIndexA: Int, uiIndexB: Int) {
        val m = quickLaunchSnapshot().toMutableList()
        if (uiIndexA !in m.indices || uiIndexB !in m.indices) return
        val tmp = m[uiIndexA]
        m[uiIndexA] = m[uiIndexB]
        m[uiIndexB] = tmp
        persistQuickLaunch(m)
    }

    suspend fun moveQuickLaunchSlot(fromUiIndex: Int, toUiIndex: Int) {
        if (fromUiIndex == toUiIndex) return
        val m = quickLaunchSnapshot().toMutableList()
        if (fromUiIndex !in m.indices || toUiIndex !in m.indices) return
        val moved = m.removeAt(fromUiIndex)
        m.add(toUiIndex, moved)
        persistQuickLaunch(m)
    }

    suspend fun mergeQuickLaunchSlots(fromUiIndex: Int, intoUiIndex: Int) {
        mergeIntentSlotsMutable(quickLaunchSnapshot().toMutableList(), fromUiIndex, intoUiIndex)
            ?.let { persistQuickLaunch(it) }
    }

    suspend fun extractQuickLaunchAppToOwnSlot(packageName: String) {
        val m = quickLaunchSnapshot().toMutableList()
        extractFromIntentFolderSlot(m, packageName)?.let { persistQuickLaunch(it) }
    }

    suspend fun removeQuickLaunchSlotAt(uiIndex: Int) {
        val slots = quickLaunchSnapshot().toMutableList()
        if (uiIndex !in slots.indices) return
        slots.removeAt(uiIndex)
        persistQuickLaunch(slots)
    }

    suspend fun setQuickLaunchFolderNameAt(uiIndex: Int, name: String?) {
        val normalized = name?.trim()?.takeIf { it.isNotEmpty() }
        val slots = quickLaunchSnapshot().toMutableList()
        if (uiIndex !in slots.indices) return
        val slot = slots[uiIndex]
        if (slot !is QuickLaunchSlot.Folder) return
        slots[uiIndex] = slot.copy(name = normalized)
        persistQuickLaunch(slots)
    }

    suspend fun setQuickLaunchFolderName(anchorPackageName: String, name: String?) {
        val normalized = name?.trim()?.takeIf { it.isNotEmpty() }
        val slots = quickLaunchSnapshot().map { slot ->
            if (slot is QuickLaunchSlot.Folder && anchorPackageName in slot.packageNames()) {
                slot.copy(name = normalized)
            } else {
                slot
            }
        }
        persistQuickLaunch(slots)
    }

    suspend fun setQuickLaunchFolderSymbolIconAt(uiIndex: Int, symbolIconName: String?) {
        val normalized = symbolIconName?.trim()?.takeIf { it.isNotEmpty() }
        val slots = quickLaunchSnapshot().toMutableList()
        if (uiIndex !in slots.indices) return
        val slot = slots[uiIndex]
        if (slot !is QuickLaunchSlot.Folder) return
        slots[uiIndex] = slot.copy(symbolIconName = normalized)
        persistQuickLaunch(slots)
    }

    suspend fun setQuickLaunchFolderSymbolIcon(anchorPackageName: String, symbolIconName: String?) {
        val normalized = symbolIconName?.trim()?.takeIf { it.isNotEmpty() }
        val slots = quickLaunchSnapshot().map { slot ->
            if (slot is QuickLaunchSlot.Folder && anchorPackageName in slot.packageNames()) {
                slot.copy(symbolIconName = normalized)
            } else {
                slot
            }
        }
        persistQuickLaunch(slots)
    }

    private suspend fun quickLaunchSnapshot(): List<QuickLaunchSlot> {
        val raw = appKvDao.getValue(QuickLaunchJson.KV_KEY)
        return normalizeIntentQuickLaunchSlots(QuickLaunchJson.decodeIntentSlots(raw))
    }

    private suspend fun persistQuickLaunch(slots: List<QuickLaunchSlot>) {
        val normalized = normalizeIntentQuickLaunchSlots(slots)
        database.withTransaction {
            appKvDao.upsert(AppKv(QuickLaunchJson.KV_KEY, QuickLaunchJson.encodeIntentSlots(normalized)))
        }
    }

    private suspend fun persistSlotKey(key: String, slots: List<QuickLaunchSlot>) {
        val normalized = normalizeQuickLaunchSlots(slots)
        database.withTransaction {
            appKvDao.upsert(AppKv(key, QuickLaunchJson.encode(normalized)))
        }
    }

    private fun removePackageFromIntentSlots(
        slots: List<QuickLaunchSlot>,
        packageName: String,
    ): List<QuickLaunchSlot> =
        slots.mapNotNull { slot ->
            when (slot) {
                is QuickLaunchSlot.Single -> {
                    if (slot.packageName == packageName) null else slot
                }
                is QuickLaunchSlot.Folder -> folderWithoutPackage(slot, packageName)
            }
        }

    private fun folderWithoutPackage(
        slot: QuickLaunchSlot.Folder,
        packageName: String,
    ): QuickLaunchSlot.Folder? {
        val apps = slot.apps.filter { it.packageName != packageName }
        val name = slot.name?.trim()?.takeIf { it.isNotEmpty() }
        if (apps.isEmpty() && slot.shortcuts.isEmpty() && name == null) return null
        return QuickLaunchSlot.Folder(name, apps, slot.symbolIconName, slot.shortcuts)
    }

    private fun removePackageFromSlots(slots: List<QuickLaunchSlot>, packageName: String): List<QuickLaunchSlot> =
        slots.mapNotNull { slot ->
            when (slot) {
                is QuickLaunchSlot.Single ->
                    if (slot.packageName == packageName) null else slot
                is QuickLaunchSlot.Folder -> {
                    val apps = slot.apps.filter { it.packageName != packageName }
                    when (apps.size) {
                        0 -> null
                        1 -> QuickLaunchSlot.Single(apps[0].packageName)
                        else -> QuickLaunchSlot.Folder(slot.name, apps, slot.symbolIconName)
                    }
                }
            }
        }

    private fun mergeSlotsMutable(
        slots: MutableList<QuickLaunchSlot>,
        fromUiIndex: Int,
        intoUiIndex: Int,
    ): List<QuickLaunchSlot>? = com.mindfulhome.data.mergeSlotsMutable(slots, fromUiIndex, intoUiIndex)

    private fun mergeIntentSlotsMutable(
        slots: MutableList<QuickLaunchSlot>,
        fromUiIndex: Int,
        intoUiIndex: Int,
    ): List<QuickLaunchSlot>? = com.mindfulhome.data.mergeIntentSlotsMutable(slots, fromUiIndex, intoUiIndex)

    private fun extractFromIntentFolderSlot(
        slots: MutableList<QuickLaunchSlot>,
        packageName: String,
    ): List<QuickLaunchSlot>? = com.mindfulhome.data.extractFromIntentFolderSlot(slots, packageName)

    private fun extractFromFolderSlot(
        slots: MutableList<QuickLaunchSlot>,
        packageName: String,
    ): List<QuickLaunchSlot>? = com.mindfulhome.data.extractFromFolderSlot(slots, packageName)

    // Todo widget (integrated)
    fun sortedOpenTodos(): Flow<List<TodoItem>> = todoDao.getOpenTodos().map { todos ->
        val nowMs = System.currentTimeMillis()
        val withDeadline = todos.filter { it.deadlineEpochMs != null }
        val withoutDeadline = todos.filter { it.deadlineEpochMs == null }
        val sortedWithDeadline = withDeadline.sortedWith(
            compareByDescending<TodoItem> { todoUrgencyScore(it, nowMs) }
                .thenBy { it.deadlineEpochMs ?: Long.MAX_VALUE }
                .thenByDescending { it.priority }
                .thenByDescending { it.updatedAtMs }
        )
        val sortedWithoutDeadline = withoutDeadline.sortedWith(
            compareByDescending<TodoItem> { it.priority }
                .thenByDescending { it.updatedAtMs }
        )
        sortedWithDeadline + sortedWithoutDeadline
    }

    suspend fun upsertTodo(
        id: Long?,
        intentText: String,
        expectedDurationMinutes: Int?,
        deadlineEpochMs: Long?,
        priority: Int,
    ): Result<Long> {
        validateTodoUpsert(intentText, expectedDurationMinutes, deadlineEpochMs, priority)?.let {
            return Result.failure(IllegalArgumentException(it))
        }
        val previous = id?.let { todoDao.getById(it) }
        val row = TodoItem(
            id = id ?: 0,
            intentText = intentText.trim(),
            expectedDurationMinutes = expectedDurationMinutes,
            deadlineEpochMs = deadlineEpochMs,
            priority = priority,
            isCompleted = previous?.isCompleted ?: false,
            updatedAtMs = System.currentTimeMillis(),
        )
        return Result.success(todoDao.upsert(row))
    }

    suspend fun setTodoCompleted(id: Long, completed: Boolean) {
        todoDao.setCompleted(id, completed, System.currentTimeMillis())
    }

    private fun todoUrgencyScore(todo: TodoItem, nowMs: Long): Double {
        val deadline = todo.deadlineEpochMs ?: return 0.0
        val duration = todo.expectedDurationMinutes ?: 0
        val timeToDeadline = max(deadline - nowMs, 60_000L)
        return (duration.toDouble() * todo.priority.toDouble()) / timeToDeadline.toDouble()
    }
}
