package com.mindfulhome.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AppKarma::class,
        UsageSession::class,
        AppFolder::class,
        FolderApp::class,
        HomeLayoutItem::class,
        AppIntent::class,
        ShelfItem::class,
        TodoItem::class,
        SessionLog::class,
        SessionLogEvent::class,
        AppKv::class,
    ],
    version = 14,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun appKarmaDao(): AppKarmaDao
    abstract fun usageSessionDao(): UsageSessionDao
    abstract fun appFolderDao(): AppFolderDao
    abstract fun homeLayoutDao(): HomeLayoutDao
    abstract fun appIntentDao(): AppIntentDao
    abstract fun shelfDao(): ShelfDao
    abstract fun todoDao(): TodoDao
    abstract fun sessionLogDao(): SessionLogDao
    abstract fun appKvDao(): AppKvDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE home_layout ADD COLUMN dockPosition INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS app_intent (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        packageName TEXT NOT NULL,
                        intentText TEXT NOT NULL,
                        timestamp INTEGER NOT NULL
                    )"""
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE app_karma ADD COLUMN isOptedOut INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS shelf_items (
                        packageName TEXT NOT NULL PRIMARY KEY,
                        position INTEGER NOT NULL DEFAULT 0
                    )"""
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS shelf_items_new (
                        packageName TEXT NOT NULL PRIMARY KEY,
                        slotPosition INTEGER NOT NULL DEFAULT 0,
                        orderInSlot INTEGER NOT NULL DEFAULT 0
                    )"""
                )
                db.execSQL(
                    """INSERT INTO shelf_items_new (packageName, slotPosition)
                       SELECT packageName, position FROM shelf_items"""
                )
                db.execSQL("DROP TABLE shelf_items")
                db.execSQL("ALTER TABLE shelf_items_new RENAME TO shelf_items")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Fix inverted isHidden condition from recordClosedOnTime bug
                db.execSQL(
                    "UPDATE app_karma SET isHidden = 0 WHERE karmaScore > -10 AND isHidden = 1"
                )
                // Remove ghost entry created by navigating to negotiate with empty packageName
                db.execSQL("DELETE FROM app_karma WHERE packageName = ''")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS session_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        startedAtMs INTEGER NOT NULL,
                        title TEXT NOT NULL
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS session_log_events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        sessionId INTEGER NOT NULL,
                        timestampMs INTEGER NOT NULL,
                        entry TEXT NOT NULL,
                        FOREIGN KEY(sessionId) REFERENCES session_logs(id) ON DELETE CASCADE
                    )"""
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_session_logs_startedAtMs ON session_logs(startedAtMs)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_session_log_events_sessionId ON session_log_events(sessionId)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_session_log_events_timestampMs ON session_log_events(timestampMs)"
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS todo_items (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        intentText TEXT NOT NULL,
                        expectedDurationMinutes INTEGER,
                        deadlineEpochMs INTEGER,
                        priority INTEGER NOT NULL,
                        isCompleted INTEGER NOT NULL,
                        updatedAtMs INTEGER NOT NULL
                    )"""
                )
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS quick_launch_items (
                        packageName TEXT NOT NULL PRIMARY KEY,
                        position INTEGER NOT NULL DEFAULT 0
                    )"""
                )
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE app_karma ADD COLUMN appNote TEXT"
                )
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS quick_launch_items_new (
                        packageName TEXT NOT NULL PRIMARY KEY,
                        slotPosition INTEGER NOT NULL DEFAULT 0,
                        orderInSlot INTEGER NOT NULL DEFAULT 0
                    )"""
                )
                db.execSQL(
                    """INSERT INTO quick_launch_items_new (packageName, slotPosition, orderInSlot)
                       SELECT packageName, position, 0 FROM quick_launch_items"""
                )
                db.execSQL("DROP TABLE quick_launch_items")
                db.execSQL("ALTER TABLE quick_launch_items_new RENAME TO quick_launch_items")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE quick_launch_items ADD COLUMN folderName TEXT DEFAULT NULL")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val cursor = db.query(
                    "SELECT packageName, slotPosition, orderInSlot, folderName FROM quick_launch_items ORDER BY slotPosition ASC, orderInSlot ASC",
                )
                val json = QuickLaunchLegacyMigration.buildJsonFromLegacyCursor(cursor)
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS app_kv (
                        key TEXT NOT NULL PRIMARY KEY,
                        value TEXT NOT NULL
                    )""",
                )
                val stmt = db.compileStatement("INSERT OR REPLACE INTO app_kv (key, value) VALUES (?, ?)")
                stmt.bindString(1, QuickLaunchJson.KV_KEY)
                stmt.bindString(2, json)
                stmt.execute()
                stmt.close()
                db.execSQL("DROP TABLE IF EXISTS quick_launch_items")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mindfulhome.db"
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                        MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                        MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                        MIGRATION_13_14,
                    )
                    .build().also { INSTANCE = it }
            }
        }
    }
}
