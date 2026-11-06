package io.github.mangi.eta.fork.meeting

import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MeetingArchiveDatabaseTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        MeetingDatabase.closeForTests()
        context.deleteDatabase("eta_meeting.db")
    }

    @After
    fun tearDown() {
        MeetingDatabase.closeForTests()
        context.deleteDatabase("eta_meeting.db")
    }

    @Test
    fun archivingAndRestoringARoomUpdatesSidebarSummary() = runBlocking {
        val roomId = MeetingRepository.createRoom(
            context = context,
            title = "Release review",
            host = MeetingParticipantDraft("lead", "Lead", "host"),
            participants = listOf(MeetingParticipantDraft("coder", "Coder", "reviewer")),
            now = 100,
        )

        assertNull(MeetingRepository.rooms(context).first().single().archivedAt)

        MeetingRepository.setRoomArchived(context, roomId, archived = true, now = 200)
        val archived = MeetingRepository.rooms(context).first().single()
        assertEquals(200L, archived.archivedAt)
        assertNotNull(MeetingRepository.room(context, roomId).first()?.archivedAt)

        MeetingRepository.setRoomArchived(context, roomId, archived = false, now = 300)
        assertNull(MeetingRepository.rooms(context).first().single().archivedAt)
    }
}
