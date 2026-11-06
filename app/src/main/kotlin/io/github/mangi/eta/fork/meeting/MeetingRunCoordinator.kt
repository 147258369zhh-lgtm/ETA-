package io.github.mangi.eta.fork.meeting

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.fork.diagnostics.RuntimeDebugTrace
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Owns meeting turns for the application lifetime. A room turn must not depend on the
 * composition that submitted it, otherwise navigating away can cancel model requests and
 * leave the persisted room stuck in RUNNING.
 */
internal object MeetingRunCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val roomJobs = ConcurrentHashMap<String, CompletableJob>()

    /**
     * Returns false only when this request was not admitted. Once true is returned the turn owns
     * an application-level job and a foreground execution lease until it settles or is stopped.
     */
    fun start(context: Context, roomId: String, rawText: String): Boolean {
        val text = rawText.trim()
        if (roomId.isBlank() || text.isEmpty()) return false

        val admission = Job(scope.coroutineContext[Job])
        if (roomJobs.putIfAbsent(roomId, admission) != null) return false

        val appContext = context.applicationContext
        val leaseId = "meeting-run:$roomId"
        val jobRef = AtomicReference<Job?>()
        if (!AgentExecutionService.acquire(appContext, leaseId, onStop = { jobRef.get()?.cancel() })) {
            roomJobs.remove(roomId, admission)
            admission.complete()
            return false
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            RuntimeDebugTrace.meetingStarted(roomId)
            try {
                MeetingRuntime.runTurn(appContext, roomId, text)
            } finally {
                RuntimeDebugTrace.meetingFinished(roomId)
                AgentExecutionService.release(leaseId)
            }
        }
        jobRef.set(job)
        job.invokeOnCompletion {
            roomJobs.remove(roomId, admission)
            admission.complete()
        }
        job.start()
        return true
    }
}
