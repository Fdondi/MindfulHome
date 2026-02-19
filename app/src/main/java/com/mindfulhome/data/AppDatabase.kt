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
        ShelfItem::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun appKarmaDao(): AppKarmaDao
    abstract fun usageSessionDao(): UsageSessionDao
    abstract fun appFolderDao(): AppFolderDao
    abstract fun homeLayoutDao(): HomeLayoutDao
    abstract fun appIntentDao(): AppIntentDao
    abstract fun shelfDao(): ShelfDao

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

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mindfulhome.db"
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                        MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7
                    )
                    .build().also { INSTANCE = it }
            }
        }
    }
}
