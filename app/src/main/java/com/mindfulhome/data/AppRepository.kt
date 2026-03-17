package com.mindfulhome.data

import kotlinx.coroutines.flow.Flow
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
    private val shelfDao = database.shelfDao()
    private val todoDao = database.todoDao()

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

    // Shelf (favorites shelf with numbered slots; >1 app in a slot = folder)
    fun shelfApps(): Flow<List<ShelfItem>> = shelfDao.getAll()
    fun appsInShelfSlot(slot: Int): Flow<List<ShelfItem>> = shelfDao.getAppsInSlot(slot)

    /** Add app as a new slot at the end of the shelf. */
    suspend fun addToShelf(packageName: String) {
        val nextSlot = shelfDao.maxSlot() + 1
        shelfDao.upsert(ShelfItem(packageName = packageName, slotPosition = nextSlot))
    }

    /** Add app into an existing slot (creates a folder if the slot already has apps). */
    suspend fun addToShelfSlot(packageName: String, slot: Int) {
        val orderInSlot = shelfDao.countInSlot(slot)
        shelfDao.upsert(ShelfItem(packageName = packageName, slotPosition = slot, orderInSlot = orderInSlot))
    }

    /** Remove app from the shelf; if the slot becomes empty, compact remaining slots. */
    suspend fun removeFromShelf(packageName: String) {
        val item = shelfDao.getByPackageName(packageName) ?: return
        shelfDao.remove(packageName)
        val remainingInSlot = shelfDao.countInSlot(item.slotPosition)
        if (remainingInSlot == 0) {
            shelfDao.compactSlotsAfter(item.slotPosition)
        }
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
