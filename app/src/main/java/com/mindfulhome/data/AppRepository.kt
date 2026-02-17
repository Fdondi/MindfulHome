package com.mindfulhome.data

import kotlinx.coroutines.flow.Flow

class AppRepository(private val database: AppDatabase) {

    private val karmaDao = database.appKarmaDao()
    private val sessionDao = database.usageSessionDao()
    private val folderDao = database.appFolderDao()
    private val layoutDao = database.homeLayoutDao()

    // Karma
    fun allKarma(): Flow<List<AppKarma>> = karmaDao.getAllKarma()
    fun hiddenApps(): Flow<List<AppKarma>> = karmaDao.getHiddenApps()

    suspend fun getKarma(packageName: String): AppKarma {
        return karmaDao.getKarma(packageName) ?: AppKarma(packageName = packageName).also {
            karmaDao.upsert(it)
        }
    }

    suspend fun adjustKarma(packageName: String, delta: Int, hideThreshold: Int = -10) {
        val current = getKarma(packageName)
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
        val recovered = (current.karmaScore + 1).coerceAtMost(0)
        karmaDao.upsert(
            current.copy(
                karmaScore = recovered,
                closedOnTimeCount = current.closedOnTimeCount + 1,
                isHidden = recovered > -10
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

    // Folders
    fun allFolders(): Flow<List<AppFolder>> = folderDao.getAllFolders()
    fun appsInFolder(folderId: Long): Flow<List<FolderApp>> = folderDao.getAppsInFolder(folderId)

    suspend fun createFolder(name: String, position: Int = 0): Long {
        return folderDao.insertFolder(AppFolder(name = name, position = position))
    }

    suspend fun addAppToFolder(folderId: Long, packageName: String, position: Int = 0) {
        folderDao.addAppToFolder(FolderApp(folderId = folderId, packageName = packageName, position = position))
    }

    suspend fun removeAppFromFolder(folderId: Long, packageName: String) {
        folderDao.removeAppFromFolder(folderId, packageName)
    }

    // Layout
    fun homeLayout(): Flow<List<HomeLayoutItem>> = layoutDao.getLayout()
    fun dockedApps(): Flow<List<HomeLayoutItem>> = layoutDao.getDockedApps()

    suspend fun setDocked(packageName: String, position: Int) {
        layoutDao.upsert(HomeLayoutItem(packageName = packageName, position = position, isDocked = true))
    }
}
