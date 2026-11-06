package io.github.mangi.eta.fork.conversations

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ConversationExtensionDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: ConversationExtensionDatabase
    private val databaseName = "eta_fork_migration_test.db"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase(databaseName)
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
        context.deleteDatabase(databaseName)
        ConversationExtensionDatabase.closeForTests()
    }

    @Test
    fun migration_from_v1_preserves_rows_and_adds_archive_tables() = runBlocking {
        createVersionOneDatabase()

        database = Room.databaseBuilder(
            context,
            ConversationExtensionDatabase::class.java,
            databaseName,
        )
            .addMigrations(MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        val dao = database.conversationExtensionDao()

        val extension = dao.extension("legacy")!!
        assertEquals("Legacy conversation", "legacy", extension.conversationId)
        assertEquals("folder-legacy", extension.folderId)
        assertEquals(7L, extension.folderSortOrder)
        assertFalse(extension.autoArchiveExempt)
        assertNull(extension.archivedAt)

        dao.upsertArchiveSettings(ConversationArchiveSettingsEntity(enabled = false, idleDays = 14))
        assertEquals(14, dao.archiveSettings()!!.idleDays)
        dao.insertArchiveEvent(
            ConversationArchiveEventEntity(
                conversationId = "legacy",
                type = "archived",
                reason = IDLE_ARCHIVE_REASON,
                isAutomatic = true,
                createdAt = 42L,
            ),
        )
    }

    private fun createVersionOneDatabase() {
        val file = File(context.getDatabasePath(databaseName).path)
        file.parentFile?.mkdirs()
        val sqlite = SQLiteDatabase.openOrCreateDatabase(file, null)
        sqlite.execSQL(
            "CREATE TABLE conversation_folders (" +
                "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, sort_order INTEGER NOT NULL, " +
                "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
        )
        sqlite.execSQL("CREATE UNIQUE INDEX index_conversation_folders_name ON conversation_folders(name)")
        sqlite.execSQL("CREATE INDEX index_conversation_folders_sort_order ON conversation_folders(sort_order)")
        sqlite.execSQL(
            "CREATE TABLE conversation_extensions (" +
                "conversation_id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, folder_id TEXT, " +
                "folder_sort_order INTEGER NOT NULL, last_active_at INTEGER NOT NULL, archived_at INTEGER, " +
                "archive_reason TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)",
        )
        sqlite.execSQL(
            "CREATE INDEX index_conversation_extensions_folder_id_folder_sort_order " +
                "ON conversation_extensions(folder_id, folder_sort_order)",
        )
        sqlite.execSQL("CREATE INDEX index_conversation_extensions_archived_at ON conversation_extensions(archived_at)")
        sqlite.execSQL("CREATE INDEX index_conversation_extensions_last_active_at ON conversation_extensions(last_active_at)")
        sqlite.execSQL(
            "INSERT INTO conversation_extensions " +
                "(conversation_id, kind, folder_id, folder_sort_order, last_active_at, archived_at, archive_reason, created_at, updated_at) " +
                "VALUES ('legacy', 'chat', 'folder-legacy', 7, 100, NULL, NULL, 10, 20)",
        )
        sqlite.version = 1
        sqlite.close()
    }
}
