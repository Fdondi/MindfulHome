package com.mindfulhome.data

import kotlinx.coroutines.flow.Flow

class AppRepository(private val database: AppDatabase) {

    private val karmaDao = database.appKarmaDao()
    private val sessionDao = database.usageSessionDao()
    private val layoutDao = database.homeLayoutDao()
    private val intentDao = database.appIntentDao()
    private val shelfDao = database.shelfDao()

    // Karma
    fun allKarma(): Flow<List<AppKarma>> = karmaDao.getAllKarma()
    fun hiddenApps(): Flow<List<AppKarma>> = karmaDao.getHiddenApps()

    suspend fun getKarma(packageName: String): AppKarma {
        if (packageName.isBlank()) return AppKarma(packageName = packageName)
        return karmaDao.getKarma(packageName) ?: AppKarma(packageName = packageName).also {
            karmaDao.upsert(it)
        }
    }

    suspend fun adjustKarma(packageName: String, delta: Int, hideThreshold: Int = -10) {
        val current = getKarma(packageName)
        if (current.isOptedOut) return
        val newScore = current.karmaScore + delta
        val shouldHide = newScore <= hideThreshold
        karmaDao.upsert(
            current.copy(
                karmaScore = newScore,
                isHidden = shouldHide
            )
        )
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

    suspend fun recordClosedOnTime(packageName: String) {
        val current = getKarma(packageName)
        if (current.isOptedOut) {
            karmaDao.upsert(current.copy(closedOnTimeCount = current.closedOnTimeCount + 1))
            return
        }
        val recovered = (current.karmaScore + 1).coerceAtMost(0)
        karmaDao.upsert(
            current.copy(
                karmaScore = recovered,
                closedOnTimeCount = current.closedOnTimeCount + 1,
                isHidden = recovered <= -10
            )
        )
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
        karmaDao.resetKarma(packageName)
    }

    suspend fun setOptedOut(packageName: String, optedOut: Boolean) {
        getKarma(packageName) // ensure the row exists before updating
        karmaDao.setOptedOut(packageName, optedOut)
        if (optedOut) {
            karmaDao.resetKarma(packageName)
        }
    }

    suspend fun dailyKarmaRecovery() {
        karmaDao.dailyKarmaRecovery()
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
}
