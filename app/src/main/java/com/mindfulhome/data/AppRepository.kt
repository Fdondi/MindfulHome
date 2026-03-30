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
            QuickLaunchSlot.Folder(name, mergedPkgs, sym)
        }
        slots[uiIndex] = merged
        persistFavorites(slots)
    }

    suspend fun removeFromFavorites(packageName: String) {
        persistFavorites(removePackageFromSlots(favoritesSnapshot(), packageName))
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
            if (slot is QuickLaunchSlot.Folder && anchorPackageName in slot.apps) {
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
            if (slot is QuickLaunchSlot.Folder && anchorPackageName in slot.apps) {
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
            normalizeQuickLaunchSlots(QuickLaunchJson.decode(raw))
        }.distinctUntilChanged()

    suspend fun addToQuickLaunch(packageName: String) {
        if (packageName.isBlank()) return
        persistQuickLaunch(quickLaunchSnapshot() + QuickLaunchSlot.Single(packageName))
    }

    /** Merge [packageName] into the slot at [uiIndex] (folder if the tile already holds multiple apps). */
    suspend fun mergePackageIntoQuickLaunchAt(uiIndex: Int, packageName: String) {
        if (packageName.isBlank()) return
        val slots = quickLaunchSnapshot().toMutableList()
        if (uiIndex !in slots.indices) return
        val existing = slots[uiIndex]
        val mergedPkgs = (existing.flattenPackages() + packageName).distinct()
        val merged: QuickLaunchSlot = if (mergedPkgs.size == 1) {
            QuickLaunchSlot.Single(mergedPkgs[0])
        } else {
            val name = (existing as? QuickLaunchSlot.Folder)?.name?.takeIf { !it.isNullOrBlank() }
            val sym = (existing as? QuickLaunchSlot.Folder)?.symbolIconName?.takeIf { !it.isNullOrBlank() }
            QuickLaunchSlot.Folder(name, mergedPkgs, sym)
        }
        slots[uiIndex] = merged
        persistQuickLaunch(slots)
    }

    suspend fun removeFromQuickLaunch(packageName: String) {
        persistQuickLaunch(removePackageFromSlots(quickLaunchSnapshot(), packageName))
    }

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
        mergeSlotsMutable(quickLaunchSnapshot().toMutableList(), fromUiIndex, intoUiIndex)?.let { persistQuickLaunch(it) }
    }

    suspend fun extractQuickLaunchAppToOwnSlot(packageName: String) {
        val m = quickLaunchSnapshot().toMutableList()
        extractFromFolderSlot(m, packageName)?.let { persistQuickLaunch(it) }
    }

    suspend fun setQuickLaunchFolderName(anchorPackageName: String, name: String?) {
        val normalized = name?.trim()?.takeIf { it.isNotEmpty() }
        val slots = quickLaunchSnapshot().map { slot ->
            if (slot is QuickLaunchSlot.Folder && anchorPackageName in slot.apps) {
                slot.copy(name = normalized)
            } else {
                slot
            }
        }
        persistQuickLaunch(slots)
    }

    suspend fun setQuickLaunchFolderSymbolIcon(anchorPackageName: String, symbolIconName: String?) {
        val normalized = symbolIconName?.trim()?.takeIf { it.isNotEmpty() }
        val slots = quickLaunchSnapshot().map { slot ->
            if (slot is QuickLaunchSlot.Folder && anchorPackageName in slot.apps) {
                slot.copy(symbolIconName = normalized)
            } else {
                slot
            }
        }
        persistQuickLaunch(slots)
    }

    private suspend fun quickLaunchSnapshot(): List<QuickLaunchSlot> {
        val raw = appKvDao.getValue(QuickLaunchJson.KV_KEY)
        return normalizeQuickLaunchSlots(QuickLaunchJson.decode(raw))
    }

    private suspend fun persistQuickLaunch(slots: List<QuickLaunchSlot>) =
        persistSlotKey(QuickLaunchJson.KV_KEY, slots)

    private suspend fun persistSlotKey(key: String, slots: List<QuickLaunchSlot>) {
        val normalized = normalizeQuickLaunchSlots(slots)
        database.withTransaction {
            appKvDao.upsert(AppKv(key, QuickLaunchJson.encode(normalized)))
        }
    }

    private fun removePackageFromSlots(slots: List<QuickLaunchSlot>, packageName: String): List<QuickLaunchSlot> =
        slots.mapNotNull { slot ->
            when (slot) {
                is QuickLaunchSlot.Single ->
                    if (slot.packageName == packageName) null else slot
                is QuickLaunchSlot.Folder -> {
                    val apps = slot.apps.filter { it != packageName }
                    when (apps.size) {
                        0 -> null
                        1 -> QuickLaunchSlot.Single(apps[0])
                        else -> QuickLaunchSlot.Folder(slot.name, apps, slot.symbolIconName)
                    }
                }
            }
        }

    private fun mergeSlotsMutable(
        slots: MutableList<QuickLaunchSlot>,
        fromUiIndex: Int,
        intoUiIndex: Int,
    ): List<QuickLaunchSlot>? {
        if (fromUiIndex == intoUiIndex) return null
        if (fromUiIndex !in slots.indices || intoUiIndex !in slots.indices) return null
        val fromSlot = slots.removeAt(fromUiIndex)
        val intoIndexAfterRemove = if (fromUiIndex < intoUiIndex) intoUiIndex - 1 else intoUiIndex
        val intoSlot = slots[intoIndexAfterRemove]
        val mergedApps = (intoSlot.flattenPackages() + fromSlot.flattenPackages()).distinct()
        val mergedName = when {
            intoSlot is QuickLaunchSlot.Folder && !intoSlot.name.isNullOrBlank() -> intoSlot.name
            fromSlot is QuickLaunchSlot.Folder && !fromSlot.name.isNullOrBlank() -> fromSlot.name
            else -> null
        }
        val mergedSymbolIcon = when {
            intoSlot is QuickLaunchSlot.Folder && !intoSlot.symbolIconName.isNullOrBlank() -> intoSlot.symbolIconName
            fromSlot is QuickLaunchSlot.Folder && !fromSlot.symbolIconName.isNullOrBlank() -> fromSlot.symbolIconName
            else -> null
        }
        val merged: QuickLaunchSlot = if (mergedApps.size == 1) {
            QuickLaunchSlot.Single(mergedApps[0])
        } else {
            QuickLaunchSlot.Folder(mergedName, mergedApps, mergedSymbolIcon)
        }
        slots[intoIndexAfterRemove] = merged
        return slots
    }

    private fun extractFromFolderSlot(
        slots: MutableList<QuickLaunchSlot>,
        packageName: String,
    ): List<QuickLaunchSlot>? {
        for (i in slots.indices) {
            val slot = slots[i]
            if (slot !is QuickLaunchSlot.Folder) continue
            if (packageName !in slot.apps) continue
            if (slot.apps.size <= 1) return null
            val remaining = slot.apps.filter { it != packageName }
            val updatedFolder: QuickLaunchSlot = when (remaining.size) {
                1 -> QuickLaunchSlot.Single(remaining[0])
                else -> QuickLaunchSlot.Folder(slot.name, remaining, slot.symbolIconName)
            }
            slots[i] = updatedFolder
            slots.add(i + 1, QuickLaunchSlot.Single(packageName))
            return slots
        }
        return null
    }

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
        if (intentText.isBlank()) return Result.failure(IllegalArgumentException("Intent is required"))
        if (deadlineEpochMs != null && (expectedDurationMinutes == null || expectedDurationMinutes <= 0)) {
            return Result.failure(IllegalArgumentException("Duration is required when deadline is set"))
        }
        if (priority !in 1..4) return Result.failure(IllegalArgumentException("Priority must be 1..4"))

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
