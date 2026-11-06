package io.github.mangi.eta.fork.meeting

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.agent.model.AgentContextSnapshot
import io.github.mangi.eta.agent.model.AgentModelClient
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.json.JSONArray
import org.json.JSONObject

internal object MeetingRepository {
    /**
     * A previous process cannot prove that an interrupted participant run produced no side effects.
     * Reopen the room for an explicit user retry instead of silently replaying the turn.
     */
    suspend fun recoverInterruptedRuns(context: Context): Int {
        val dao = MeetingDatabase.get(context).meetingDao()
        val rooms = dao.runningRooms()
        rooms.forEach { room ->
            dao.unfinishedTasks(room.id).forEach { task ->
                updateTask(
                    context = context,
                    task = task,
                    status = MeetingTaskStatus.FAILED,
                    errorMessage = "应用进程中断，未自动重放可能产生外部副作用的任务",
                )
            }
            appendMessage(
                context = context,
                roomId = room.id,
                authorType = MeetingAuthorType.SYSTEM,
                authorProfile = null,
                authorName = "Meeting",
                content = "上一轮在应用退出时中断。为避免重复执行外部操作，本轮没有自动重放；请检查已有结果后明确再次发送。",
            )
            appendEvent(
                context = context,
                roomId = room.id,
                taskId = null,
                type = "interrupted_run_recovered",
                payload = JSONObject().put("retry", "user_required"),
            )
            dao.updateRoom(
                room.copy(
                    status = MeetingRoomStatus.WAITING_FOR_USER,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
        return rooms.size
    }

    fun rooms(context: Context): Flow<List<MeetingRoomSummaryRow>> =
        MeetingDatabase.get(context).meetingDao().roomSummariesFlow()

    fun room(context: Context, roomId: String): Flow<MeetingRoomEntity?> =
        MeetingDatabase.get(context).meetingDao().roomFlow(roomId)

    fun snapshot(context: Context, roomId: String): Flow<MeetingRoomSnapshot?> {
        val dao = MeetingDatabase.get(context).meetingDao()
        return combine(
            dao.roomFlow(roomId),
            dao.participantsFlow(roomId),
            dao.messagesFlow(roomId),
            dao.tasksFlow(roomId),
        ) { room, participants, messages, tasks ->
            room?.let {
                MeetingRoomSnapshot(
                    room = it,
                    participants = participants,
                    messages = messages,
                    tasks = tasks,
                )
            }
        }
    }

    suspend fun createRoom(
        context: Context,
        title: String,
        host: MeetingParticipantDraft,
        participants: List<MeetingParticipantDraft>,
        now: Long = System.currentTimeMillis(),
    ): String {
        require(title.isNotBlank()) { "会议室名称不能为空" }
        require(host.profileName.isNotBlank()) { "主持人不能为空" }
        val roomId = UUID.randomUUID().toString()
        val normalizedParticipants = buildList {
            add(host.copy(isHost = true))
            participants
                .filter { it.profileName.isNotBlank() && it.profileName != host.profileName }
                .forEach { add(it.copy(isHost = false)) }
        }.distinctBy { it.profileName }
        val database = MeetingDatabase.get(context)
        database.withTransaction {
            val dao = database.meetingDao()
            dao.insertRoom(
                MeetingRoomEntity(
                    id = roomId,
                    title = title.trim(),
                    hostProfile = host.profileName,
                    status = MeetingRoomStatus.IDLE,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            dao.insertParticipants(
                normalizedParticipants.mapIndexed { index, draft ->
                    MeetingParticipantEntity(
                        roomId = roomId,
                        profileName = draft.profileName,
                        displayName = draft.displayName.trim().ifBlank { draft.profileName },
                        profileSummary = draft.profileSummary.trim(),
                        providerId = draft.providerId?.trim()?.takeIf(String::isNotEmpty),
                        modelId = draft.modelId?.trim()?.takeIf(String::isNotEmpty),
                        isHost = index == 0,
                        sortOrder = index,
                        enabled = true,
                    )
                },
            )
            dao.insertEvent(
                MeetingEventEntity(
                    roomId = roomId,
                    type = "room_created",
                    payloadJson = JSONObject()
                        .put("host", host.profileName)
                        .put("participants", JSONArray(normalizedParticipants.map { it.profileName }))
                        .toString(),
                    createdAt = now,
                ),
            )
        }
        return roomId
    }

    suspend fun appendMessage(
        context: Context,
        roomId: String,
        authorType: String,
        authorProfile: String?,
        authorName: String,
        content: String,
        mentions: List<String> = emptyList(),
        taskId: String? = null,
        messageId: String = UUID.randomUUID().toString(),
        now: Long = System.currentTimeMillis(),
    ): MeetingMessageEntity {
        val database = MeetingDatabase.get(context)
        return database.withTransaction {
            val dao = database.meetingDao()
            val sortIndex = dao.maxMessageSortIndex(roomId) + 1
            val message = MeetingMessageEntity(
                id = messageId,
                roomId = roomId,
                sortIndex = sortIndex,
                authorType = authorType,
                authorProfile = authorProfile,
                authorName = authorName,
                content = content,
                mentionsJson = JSONArray(mentions.distinct()).toString(),
                taskId = taskId,
                createdAt = now,
            )
            dao.insertMessage(message)
            dao.room(roomId)?.let { dao.updateRoom(it.copy(updatedAt = now)) }
            message
        }
    }

    suspend fun createTask(
        context: Context,
        roomId: String,
        participantProfile: String,
        instruction: String,
        now: Long = System.currentTimeMillis(),
    ): MeetingTaskEntity {
        val task = MeetingTaskEntity(
            id = UUID.randomUUID().toString(),
            roomId = roomId,
            participantProfile = participantProfile,
            instruction = instruction,
            status = MeetingTaskStatus.QUEUED,
            createdAt = now,
            updatedAt = now,
        )
        val dao = MeetingDatabase.get(context).meetingDao()
        dao.insertTask(task)
        appendEvent(context, roomId, task.id, "task_queued", JSONObject().put("participant", participantProfile), now)
        return task
    }

    suspend fun updateTask(
        context: Context,
        task: MeetingTaskEntity,
        status: String,
        resultMessageId: String? = task.resultMessageId,
        errorMessage: String? = task.errorMessage,
        now: Long = System.currentTimeMillis(),
    ) {
        MeetingDatabase.get(context).meetingDao().updateTask(
            task.copy(
                status = status,
                resultMessageId = resultMessageId,
                errorMessage = errorMessage,
                updatedAt = now,
            ),
        )
        appendEvent(
            context = context,
            roomId = task.roomId,
            taskId = task.id,
            type = "task_$status",
            payload = JSONObject().put("participant", task.participantProfile).apply {
                errorMessage?.let { put("error", it.take(1_000)) }
            },
            now = now,
        )
    }

    suspend fun setRoomStatus(
        context: Context,
        roomId: String,
        status: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val dao = MeetingDatabase.get(context).meetingDao()
        val room = dao.room(roomId) ?: return
        dao.updateRoom(room.copy(status = status, updatedAt = now))
        appendEvent(context, roomId, null, "room_$status", JSONObject(), now)
    }

    suspend fun renameRoom(
        context: Context,
        roomId: String,
        title: String,
        now: Long = System.currentTimeMillis(),
    ) {
        val normalized = title.trim()
        if (normalized.isEmpty()) return
        val database = MeetingDatabase.get(context)
        database.withTransaction {
            val dao = database.meetingDao()
            val room = dao.room(roomId) ?: return@withTransaction
            dao.updateRoom(room.copy(title = normalized.take(120), updatedAt = now))
            dao.insertEvent(
                MeetingEventEntity(
                    roomId = roomId,
                    type = "room_renamed",
                    payloadJson = JSONObject().put("title", normalized.take(120)).toString(),
                    createdAt = now,
                ),
            )
        }
    }

    suspend fun setRoomArchived(
        context: Context,
        roomId: String,
        archived: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        val database = MeetingDatabase.get(context)
        database.withTransaction {
            val dao = database.meetingDao()
            val room = dao.room(roomId) ?: return@withTransaction
            require(!archived || room.status != MeetingRoomStatus.RUNNING) {
                "会议室运行结束后才能归档"
            }
            val archivedAt = if (archived) now else null
            if (room.archivedAt == archivedAt) return@withTransaction
            dao.updateRoom(room.copy(archivedAt = archivedAt, updatedAt = now))
            dao.insertEvent(
                MeetingEventEntity(
                    roomId = roomId,
                    type = if (archived) "room_archived" else "room_restored",
                    payloadJson = JSONObject()
                        .put("archivedAt", archivedAt)
                        .toString(),
                    createdAt = now,
                ),
            )
        }
    }

    suspend fun appendEvent(
        context: Context,
        roomId: String,
        taskId: String?,
        type: String,
        payload: JSONObject,
        now: Long = System.currentTimeMillis(),
    ) {
        MeetingDatabase.get(context).meetingDao().insertEvent(
            MeetingEventEntity(
                roomId = roomId,
                taskId = taskId,
                type = type,
                payloadJson = payload.toString(),
                createdAt = now,
            ),
        )
    }

    suspend fun deleteRoom(context: Context, roomId: String) {
        val database = MeetingDatabase.get(context)
        database.withTransaction {
            val dao = database.meetingDao()
            dao.deleteMessages(roomId)
            dao.deleteTasks(roomId)
            dao.deleteEvents(roomId)
            dao.deleteParticipantContexts(roomId)
            dao.deleteParticipants(roomId)
            dao.deleteRoom(roomId)
        }
    }

    suspend fun participantHistory(
        context: Context,
        roomId: String,
        profileName: String,
        messages: List<MeetingMessageEntity>,
    ): List<AgentModelClient.ConversationMessage> {
        val checkpoint = MeetingDatabase.get(context).meetingDao()
            .participantContext(roomId, profileName)
            ?: return MeetingContextHistory.forParticipant(messages, profileName)
        val compacted = AgentContextSnapshot.decode(checkpoint.snapshotJson)?.messages
            ?: return MeetingContextHistory.forParticipant(messages, profileName)
        val appended = MeetingContextHistory.forParticipant(
            messages = messages.filter { it.sortIndex > checkpoint.lastMessageSortIndex },
            participantProfile = profileName,
        )
        return compacted + appended
    }

    suspend fun saveParticipantContext(
        context: Context,
        roomId: String,
        profileName: String,
        snapshot: AgentContextSnapshot?,
        lastMessageSortIndex: Int,
        now: Long = System.currentTimeMillis(),
    ) {
        snapshot ?: return
        MeetingDatabase.get(context).meetingDao().upsertParticipantContext(
            MeetingParticipantContextEntity(
                roomId = roomId,
                profileName = profileName,
                snapshotJson = snapshot.encode(),
                lastMessageSortIndex = lastMessageSortIndex,
                updatedAt = now,
            ),
        )
    }
}

internal data class MeetingParticipantDraft(
    val profileName: String,
    val displayName: String,
    val profileSummary: String,
    val providerId: String? = null,
    val modelId: String? = null,
    val isHost: Boolean = false,
)
