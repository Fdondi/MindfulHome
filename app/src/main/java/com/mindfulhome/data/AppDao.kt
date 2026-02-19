package com.mindfulhome.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AppKarmaDao {

    @Query("SELECT * FROM app_karma WHERE packageName = :packageName")
    suspend fun getKarma(packageName: String): AppKarma?

    @Query("SELECT * FROM app_karma")
    fun getAllKarma(): Flow<List<AppKarma>>

    @Query("SELECT * FROM app_karma WHERE isHidden = 1")
    fun getHiddenApps(): Flow<List<AppKarma>>

    @Query("SELECT * FROM app_karma WHERE isHidden = 0")
    fun getVisibleApps(): Flow<List<AppKarma>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(karma: AppKarma)

    @Update
    suspend fun update(karma: AppKarma)

    @Query("UPDATE app_karma SET karmaScore = karmaScore + :delta WHERE packageName = :packageName")
    suspend fun adjustKarma(packageName: String, delta: Int)

    @Query("UPDATE app_karma SET isHidden = :hidden WHERE packageName = :packageName")
    suspend fun setHidden(packageName: String, hidden: Boolean)

    @Query("UPDATE app_karma SET karmaScore = MIN(karmaScore + 1, 0) WHERE isHidden = 1 AND karmaScore < 0")
    suspend fun dailyKarmaRecovery()
}

@Dao
interface UsageSessionDao {

    @Insert
    suspend fun insert(session: UsageSession): Long

    @Update
    suspend fun update(session: UsageSession)

    @Query("SELECT * FROM usage_sessions WHERE id = :id")
    suspend fun getSession(id: Long): UsageSession?

    @Query("SELECT * FROM usage_sessions WHERE packageName = :packageName ORDER BY startTimestamp DESC LIMIT :limit")
    suspend fun getRecentSessions(packageName: String, limit: Int = 20): List<UsageSession>

    @Query("SELECT * FROM usage_sessions ORDER BY startTimestamp DESC LIMIT :limit")
    suspend fun getAllRecent(limit: Int = 50): List<UsageSession>
}

@Dao
interface AppFolderDao {

    @Query("SELECT * FROM app_folders ORDER BY position")
    fun getAllFolders(): Flow<List<AppFolder>>

    @Insert
    suspend fun insertFolder(folder: AppFolder): Long

    @Query("DELETE FROM app_folders WHERE id = :folderId")
    suspend fun deleteFolder(folderId: Long)

    @Query("SELECT * FROM folder_apps WHERE folderId = :folderId ORDER BY position")
    fun getAppsInFolder(folderId: Long): Flow<List<FolderApp>>

    @Query("SELECT * FROM folder_apps")
    fun getAllFolderApps(): Flow<List<FolderApp>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addAppToFolder(folderApp: FolderApp)

    @Query("DELETE FROM folder_apps WHERE folderId = :folderId AND packageName = :packageName")
    suspend fun removeAppFromFolder(folderId: Long, packageName: String)

    @Query("DELETE FROM folder_apps WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: Long)

    @Query("UPDATE app_folders SET name = :name WHERE id = :folderId")
    suspend fun renameFolder(folderId: Long, name: String)

    @Query("UPDATE app_folders SET position = :position WHERE id = :folderId")
    suspend fun updateFolderPosition(folderId: Long, position: Int)
}

@Dao
interface AppIntentDao {

    @Insert
    suspend fun insert(intent: AppIntent)

    @Query("SELECT * FROM app_intent WHERE packageName = :packageName ORDER BY timestamp DESC")
    suspend fun getIntentsForApp(packageName: String): List<AppIntent>

    @Query("SELECT * FROM app_intent ORDER BY timestamp DESC")
    fun getAllIntents(): Flow<List<AppIntent>>
}

@Dao
interface HomeLayoutDao {

    @Query("SELECT * FROM home_layout ORDER BY position")
    fun getLayout(): Flow<List<HomeLayoutItem>>

    @Query("SELECT * FROM home_layout WHERE isDocked = 1 ORDER BY dockPosition")
    fun getDockedApps(): Flow<List<HomeLayoutItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: HomeLayoutItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<HomeLayoutItem>)

    @Query("SELECT * FROM home_layout WHERE packageName = :packageName")
    suspend fun getByPackageName(packageName: String): HomeLayoutItem?

    @Query("SELECT COUNT(*) FROM home_layout WHERE isDocked = 1")
    suspend fun dockedCount(): Int

    @Query("DELETE FROM home_layout WHERE packageName = :packageName")
    suspend fun remove(packageName: String)
}
