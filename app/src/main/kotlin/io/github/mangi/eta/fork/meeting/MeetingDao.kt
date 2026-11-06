package io.github.mangi.eta.fork.meeting

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
internal interface MeetingDao {
    @Query("SELECT * FROM meeting_rooms ORDER BY updated_at DESC")
    fun roomsFlow(): Flow<List<MeetingRoomEntity>>

    @Query(
        "SELECT meeting_rooms.id, meeting_rooms.title, meeting_rooms.host_profile, " +
            "meeting_rooms.status, meeting_rooms.created_at, meeting_rooms.updated_at, " +
            "meeting_rooms.archived_at, " +
            "COUNT(meeting_participants.profile_name) AS participant_count " +
            "FROM meeting_rooms LEFT JOIN meeting_participants " +
            "ON meeting_participants.room_id = meeting_rooms.id AND meeting_participants.enabled = 1 " +
            "GROUP BY meeting_rooms.id ORDER BY meeting_rooms.updated_at DESC"
    )
    fun roomSummariesFlow(): Flow<List<MeetingRoomSummaryRow>>

    @Query("SELECT * FROM meeting_rooms WHERE id = :roomId")
    fun roomFlow(roomId: String): Flow<MeetingRoomEntity?>

    @Query("SELECT * FROM meeting_rooms WHERE id = :roomId")
    suspend fun room(roomId: String): MeetingRoomEntity?

    @Query("SELECT * FROM meeting_rooms WHERE status = 'running' ORDER BY updated_at, id")
    suspend fun runningRooms(): List<MeetingRoomEntity>

    @Query("SELECT * FROM meeting_tasks WHERE room_id = :roomId AND status IN ('queued', 'running') ORDER BY created_at, id")
    suspend fun unfinishedTasks(roomId: String): List<MeetingTaskEntity>

    // The room UI still shows disabled agents so their process and history remain inspectable.
    // Dispatch queries filter enabled participants separately.
    @Query("SELECT * FROM meeting_participants WHERE room_id = :roomId ORDER BY is_host DESC, sort_order ASC")
    fun participantsFlow(roomId: String): Flow<List<MeetingParticipantEntity>>

    @Query("SELECT * FROM meeting_participants WHERE room_id = :roomId ORDER BY is_host DESC, sort_order ASC")
    suspend fun participants(roomId: String): List<MeetingParticipantEntity>

    @Query("SELECT * FROM meeting_messages WHERE room_id = :roomId ORDER BY sort_index ASC")
    fun messagesFlow(roomId: String): Flow<List<MeetingMessageEntity>>

    @Query("SELECT * FROM meeting_tasks WHERE room_id = :roomId ORDER BY created_at ASC")
    fun tasksFlow(roomId: String): Flow<List<MeetingTaskEntity>>

    @Query("SELECT COALESCE(MAX(sort_index), -1) FROM meeting_messages WHERE room_id = :roomId")
    suspend fun maxMessageSortIndex(roomId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoom(room: MeetingRoomEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertParticipants(participants: List<MeetingParticipantEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MeetingMessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: MeetingTaskEntity)

    @Insert
    suspend fun insertEvent(event: MeetingEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParticipantContext(context: MeetingParticipantContextEntity)

    @Query(
        "SELECT * FROM meeting_participant_contexts WHERE room_id = :roomId AND profile_name = :profileName",
    )
    suspend fun participantContext(roomId: String, profileName: String): MeetingParticipantContextEntity?

    @Update
    suspend fun updateRoom(room: MeetingRoomEntity)

    @Update
    suspend fun updateTask(task: MeetingTaskEntity)

    @Query("DELETE FROM meeting_rooms WHERE id = :roomId")
    suspend fun deleteRoom(roomId: String)

    @Query("DELETE FROM meeting_participants WHERE room_id = :roomId")
    suspend fun deleteParticipants(roomId: String)

    @Query("DELETE FROM meeting_messages WHERE room_id = :roomId")
    suspend fun deleteMessages(roomId: String)

    @Query("DELETE FROM meeting_tasks WHERE room_id = :roomId")
    suspend fun deleteTasks(roomId: String)

    @Query("DELETE FROM meeting_events WHERE room_id = :roomId")
    suspend fun deleteEvents(roomId: String)

    @Query("DELETE FROM meeting_participant_contexts WHERE room_id = :roomId")
    suspend fun deleteParticipantContexts(roomId: String)
}
