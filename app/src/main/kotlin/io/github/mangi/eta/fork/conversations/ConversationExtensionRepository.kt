package io.github.mangi.eta.fork.conversations

import android.content.Context
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

internal object ConversationExtensionRepository {
    fun snapshot(context: Context): Flow<ConversationExtensionSnapshot> {
        val dao = ConversationExtensionDatabase.get(context).conversationExtensionDao()
        return combine(
            dao.foldersFlow(),
            dao.extensionsFlow(),
            dao.archiveSettingsFlow(),
        ) { folders, extensions, settings ->
            ConversationExtensionSnapshot(
                folders = ConversationFolderOrdering.sortFolders(folders.map { it.toModel() }),
                conversations = extensions.associate { it.conversationId to it.toModel() },
                archiveSettings = settings?.toModel() ?: ConversationArchiveSettings(),
            )
        }.distinctUntilChanged()
    }

    suspend fun ensureConversations(
        context: Context,
        conversationIds: Collection<String>,
        defaultLastActiveAt: Long = System.currentTimeMillis(),
        lastActiveAt: (String) -> Long? = { null },
    ) {
        val ids = conversationIds.filter(String::isNotBlank).distinct()
        if (ids.isEmpty()) return
        ConversationExtensionDatabase.get(context).conversationExtensionDao().insertExtensions(
            ids.map { id ->
                ConversationExtensionEntity(
                    conversationId = id,
                    lastActiveAt = lastActiveAt(id)?.takeIf { it > 0L } ?: defaultLastActiveAt,
                    createdAt = defaultLastActiveAt,
                    updatedAt = defaultLastActiveAt,
                )
            },
        )
    }

    suspend fun touchConversation(
        context: Context,
        conversationId: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val dao = ConversationExtensionDatabase.get(context).conversationExtensionDao()
        if (dao.extension(conversationId)?.archivedAt != null) return
        dao.touchLastActive(conversationId, now)
    }

    suspend fun createFolder(
        context: Context,
        name: String,
        now: Long = System.currentTimeMillis(),
    ): String {
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "Folder name must not be blank" }
        val database = ConversationExtensionDatabase.get(context)
        return database.withTransaction {
            val dao = database.conversationExtensionDao()
            val id = UUID.randomUUID().toString()
            dao.upsertFolder(
                ConversationFolderEntity(
                    id = id,
                    name = normalized,
                    sortOrder = dao.maxFolderSortOrder() + 1,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            id
        }
    }

    suspend fun renameFolder(
        context: Context,
        folder: ConversationFolder,
        name: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "Folder name must not be blank" }
        ConversationExtensionDatabase.get(context).conversationExtensionDao().upsertFolder(
            ConversationFolderEntity(
                id = folder.id,
                name = normalized,
                sortOrder = folder.sortOrder,
                createdAt = folder.createdAt,
                updatedAt = now,
            ),
        )
    }

    suspend fun deleteFolder(
        context: Context,
        folderId: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val database = ConversationExtensionDatabase.get(context)
        database.withTransaction {
            val dao = database.conversationExtensionDao()
            dao.clearFolderMembers(folderId, now)
            dao.deleteFolder(folderId)
        }
    }

    suspend fun moveConversation(
        context: Context,
        conversationId: String,
        folderId: String?,
        now: Long = System.currentTimeMillis(),
    ) {
        val database = ConversationExtensionDatabase.get(context)
        database.withTransaction {
            val dao = database.conversationExtensionDao()
            val sortOrder = folderId?.let { dao.maxFolderConversationSortOrder(it) + 1 } ?: 0L
            dao.updateFolder(conversationId, folderId, sortOrder, now)
        }
    }

    suspend fun archiveConversation(
        context: Context,
        conversationId: String,
        reason: String = MANUAL_ARCHIVE_REASON,
        isAutomatic: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ) {
        val database = ConversationExtensionDatabase.get(context)
        database.withTransaction {
            val dao = database.conversationExtensionDao()
            dao.markArchived(conversationId, now, reason, now)
            dao.insertArchiveEvent(
                ConversationArchiveEventEntity(
                    conversationId = conversationId,
                    type = "archived",
                    reason = reason,
                    isAutomatic = isAutomatic,
                    createdAt = now,
                ),
            )
        }
    }

    suspend fun restoreConversation(
        context: Context,
        conversationId: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val database = ConversationExtensionDatabase.get(context)
        database.withTransaction {
            val dao = database.conversationExtensionDao()
            dao.markRestored(conversationId, now)
            dao.insertArchiveEvent(
                ConversationArchiveEventEntity(
                    conversationId = conversationId,
                    type = "restored",
                    reason = MANUAL_ARCHIVE_REASON,
                    isAutomatic = false,
                    createdAt = now,
                ),
            )
        }
    }

    suspend fun setAutoArchiveExempt(
        context: Context,
        conversationId: String,
        exempt: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        ConversationExtensionDatabase.get(context).conversationExtensionDao()
            .setArchiveExempt(conversationId, exempt, now)
    }

    suspend fun setKind(
        context: Context,
        conversationId: String,
        kind: String,
        now: Long = System.currentTimeMillis(),
    ) {
        ConversationExtensionDatabase.get(context).conversationExtensionDao()
            .setKind(conversationId, kind, now)
    }

    suspend fun archiveSettings(context: Context): ConversationArchiveSettings =
        ConversationExtensionDatabase.get(context).conversationExtensionDao()
            .archiveSettings()?.toModel() ?: ConversationArchiveSettings()

    suspend fun updateArchiveSettings(
        context: Context,
        transform: (ConversationArchiveSettings) -> ConversationArchiveSettings,
    ): ConversationArchiveSettings {
        val database = ConversationExtensionDatabase.get(context)
        return database.withTransaction {
            val dao = database.conversationExtensionDao()
            val next = transform(dao.archiveSettings()?.toModel() ?: ConversationArchiveSettings())
            dao.upsertArchiveSettings(next.toEntity())
            next
        }
    }

    suspend fun archiveEligibleConversations(
        context: Context,
        eligibility: (String) -> ConversationArchiveEligibility,
        now: Long = System.currentTimeMillis(),
    ): List<String> {
        val database = ConversationExtensionDatabase.get(context)
        val settings = database.conversationExtensionDao().archiveSettings()?.toModel()
            ?: ConversationArchiveSettings()
        val eligible = ConversationArchivePolicy.eligibleConversationIds(
            extensions = database.conversationExtensionDao().extensions().map { it.toModel() },
            settings = settings,
            nowMillis = now,
            eligibility = eligibility,
        )
        eligible.forEach { id ->
            archiveConversation(context, id, IDLE_ARCHIVE_REASON, isAutomatic = true, now = now)
        }
        return eligible
    }

    suspend fun deleteConversation(
        context: Context,
        conversationId: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val database = ConversationExtensionDatabase.get(context)
        database.withTransaction {
            val dao = database.conversationExtensionDao()
            dao.deleteArchiveEvents(conversationId)
            dao.deleteExtension(conversationId)
        }
    }

    suspend fun recordArchiveCheck(
        context: Context,
        checkedAt: Long,
        error: String?,
    ) {
        updateArchiveSettings(context) { settings ->
            settings.copy(lastCheckedAt = checkedAt, lastError = error)
        }
    }
}
