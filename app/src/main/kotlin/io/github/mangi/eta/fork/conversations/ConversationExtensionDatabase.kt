package io.github.mangi.eta.fork.conversations

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationFolderEntity::class,
        ConversationExtensionEntity::class,
        ConversationArchiveEventEntity::class,
        ConversationArchiveSettingsEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
internal abstract class ConversationExtensionDatabase : RoomDatabase() {
    abstract fun conversationExtensionDao(): ConversationExtensionDao

    companion object {
        const val DATABASE_NAME = "eta_fork.db"

        @Volatile
        private var instance: ConversationExtensionDatabase? = null

        fun get(context: Context): ConversationExtensionDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ConversationExtensionDatabase::class.java,
                    DATABASE_NAME,
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }

        @VisibleForTesting
        internal fun closeForTests() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}

internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "ALTER TABLE conversation_extensions " +
                "ADD COLUMN auto_archive_exempt INTEGER NOT NULL DEFAULT 0",
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS conversation_archive_events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "conversation_id TEXT NOT NULL, " +
                "type TEXT NOT NULL, " +
                "reason TEXT NOT NULL, " +
                "is_automatic INTEGER NOT NULL, " +
                "created_at INTEGER NOT NULL)",
        )
        database.execSQL(
            "CREATE INDEX IF NOT EXISTS index_conversation_archive_events_conversation_id_created_at " +
                "ON conversation_archive_events(conversation_id, created_at)",
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS conversation_archive_settings (" +
                "id INTEGER NOT NULL, " +
                "enabled INTEGER NOT NULL, " +
                "idle_days INTEGER NOT NULL, " +
                "exempt_pinned INTEGER NOT NULL, " +
                "exempt_meetings INTEGER NOT NULL, " +
                "exempt_running INTEGER NOT NULL, " +
                "last_checked_at INTEGER, " +
                "last_error TEXT, " +
                "PRIMARY KEY(id))",
        )
    }
}
