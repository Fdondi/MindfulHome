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
interface AppKvDao {

    @Query("SELECT value FROM app_kv WHERE key = :key")
    fun observeValue(key: String): Flow<String?>

    @Query("SELECT value FROM app_kv WHERE key = :key")
    suspend fun getValue(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(kv: AppKv)
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

    @Query(
        """
        SELECT s.id, s.startedAtMs, s.title, COUNT(e.id) as eventCount
        FROM session_logs s
        LEFT JOIN session_log_events e ON e.sessionId = s.id
        WHERE s.startedAtMs >= :startMs AND s.startedAtMs < :endMs
        GROUP BY s.id
        HAVING COUNT(e.id) > 0
        ORDER BY s.startedAtMs ASC
        """
    )
    suspend fun getSessionsWithCountsInRange(startMs: Long, endMs: Long): List<SessionLogWithCount>

    /**
     * Distinct local calendar days (yyyy-MM-dd) that have at least one session with events,
     * most recent first. Matches grouping in [com.mindfulhome.ui.logs.LogsScreen].
     */
    @Query(
        """
        SELECT date(s.startedAtMs / 1000, 'unixepoch', 'localtime') AS dayKey
        FROM session_logs s
        INNER JOIN session_log_events e ON e.sessionId = s.id
        GROUP BY date(s.startedAtMs / 1000, 'unixepoch', 'localtime')
        ORDER BY dayKey DESC
        LIMIT :limit
        """
    )
    suspend fun getRecentDistinctLocalDaysWithLogs(limit: Int): List<String>
}

@Dao
interface DailyLogSummaryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(summary: DailyLogSummary)

    @Query("SELECT * FROM daily_log_summaries WHERE day = :day LIMIT 1")
    suspend fun getByDay(day: String): DailyLogSummary?

    @Query("SELECT * FROM daily_log_summaries ORDER BY day DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<DailyLogSummary>
}
