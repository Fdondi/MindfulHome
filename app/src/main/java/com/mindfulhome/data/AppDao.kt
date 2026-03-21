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

    @Query("SELECT * FROM app_karma WHERE karmaScore < 0 AND isOptedOut = 0")
    suspend fun getUnderwaterAppsForRecovery(): List<AppKarma>

    @Query("SELECT * FROM app_karma WHERE isHidden = 0")
    fun getVisibleApps(): Flow<List<AppKarma>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(karma: AppKarma)

    @Update
    suspend fun update(karma: AppKarma)
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

@Dao
interface ShelfDao {

    @Query("SELECT * FROM shelf_items ORDER BY slotPosition, orderInSlot")
    fun getAll(): Flow<List<ShelfItem>>

    @Query("SELECT * FROM shelf_items WHERE slotPosition = :slot ORDER BY orderInSlot")
    fun getAppsInSlot(slot: Int): Flow<List<ShelfItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ShelfItem)

    @Query("DELETE FROM shelf_items WHERE packageName = :packageName")
    suspend fun remove(packageName: String)

    @Query("SELECT COUNT(*) FROM shelf_items WHERE slotPosition = :slot")
    suspend fun countInSlot(slot: Int): Int

    @Query("SELECT COALESCE(MAX(slotPosition), 0) FROM shelf_items")
    suspend fun maxSlot(): Int

    @Query("SELECT COUNT(DISTINCT slotPosition) FROM shelf_items")
    suspend fun slotCount(): Int

    @Query("SELECT * FROM shelf_items WHERE packageName = :packageName")
    suspend fun getByPackageName(packageName: String): ShelfItem?

    @Query("UPDATE shelf_items SET slotPosition = slotPosition - 1 WHERE slotPosition > :removedSlot")
    suspend fun compactSlotsAfter(removedSlot: Int)
}

@Dao
interface TodoDao {

    @Query("SELECT * FROM todo_items WHERE isCompleted = 0")
    fun getOpenTodos(): Flow<List<TodoItem>>

    @Query("SELECT * FROM todo_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TodoItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: TodoItem): Long

    @Query("UPDATE todo_items SET isCompleted = :completed, updatedAtMs = :updatedAtMs WHERE id = :id")
    suspend fun setCompleted(id: Long, completed: Boolean, updatedAtMs: Long = System.currentTimeMillis())
}

@Dao
interface QuickLaunchDao {

    @Query("SELECT * FROM quick_launch_items ORDER BY position")
    fun getAll(): Flow<List<QuickLaunchItem>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: QuickLaunchItem)

    @Query("DELETE FROM quick_launch_items WHERE packageName = :packageName")
    suspend fun remove(packageName: String)

    @Query("SELECT COALESCE(MAX(position), -1) FROM quick_launch_items")
    suspend fun maxPosition(): Int

    @Query("UPDATE quick_launch_items SET position = :position WHERE packageName = :packageName")
    suspend fun updatePosition(packageName: String, position: Int)

    @Query("UPDATE quick_launch_items SET folderId = :folderId WHERE packageName = :packageName")
    suspend fun setFolder(packageName: String, folderId: Long?)

    @Query("SELECT * FROM quick_launch_items WHERE folderId = :folderId ORDER BY position")
    suspend fun getInFolderOnce(folderId: Long): List<QuickLaunchItem>

    @Query("SELECT COUNT(*) FROM quick_launch_items WHERE folderId = :folderId")
    suspend fun countInFolder(folderId: Long): Int
}

@Dao
interface QuickLaunchFolderDao {

    @Query("SELECT * FROM ql_folders ORDER BY position")
    fun getAll(): Flow<List<QuickLaunchFolder>>

    @Insert
    suspend fun insert(folder: QuickLaunchFolder): Long

    @Query("DELETE FROM ql_folders WHERE id = :folderId")
    suspend fun delete(folderId: Long)

    @Query("UPDATE ql_folders SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE ql_folders SET position = :position WHERE id = :id")
    suspend fun updatePosition(id: Long, position: Int)
}

data class SessionLogWithCount(
    val id: Long,
    val startedAtMs: Long,
    val title: String,
    val eventCount: Int,
)

@Dao
interface SessionLogDao {

    @Insert
    suspend fun insertSession(session: SessionLog): Long

    @Insert
    suspend fun insertEvent(event: SessionLogEvent): Long

    @Query(
        """
        SELECT s.id, s.startedAtMs, s.title, COUNT(e.id) as eventCount
        FROM session_logs s
        LEFT JOIN session_log_events e ON e.sessionId = s.id
        GROUP BY s.id
        HAVING COUNT(e.id) > 0
        ORDER BY s.startedAtMs DESC
        """
    )
    suspend fun getSessionsWithCounts(): List<SessionLogWithCount>

    @Query(
        """
        SELECT s.id, s.startedAtMs, s.title, COUNT(e.id) as eventCount
        FROM session_logs s
        LEFT JOIN session_log_events e ON e.sessionId = s.id
        GROUP BY s.id
        HAVING COUNT(e.id) > 0
        ORDER BY s.startedAtMs DESC
        LIMIT 1
        """
    )
    suspend fun getLatestSessionWithCount(): SessionLogWithCount?

    @Query("SELECT * FROM session_log_events WHERE sessionId = :sessionId ORDER BY timestampMs ASC, id ASC")
    suspend fun getEventsForSession(sessionId: Long): List<SessionLogEvent>
}
