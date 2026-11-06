package io.github.mangi.eta.fork.tasks

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.fork.a2a.A2AAgentRepository
import io.github.mangi.eta.fork.a2a.A2AHttpClient
import io.github.mangi.eta.fork.a2a.A2AMessage
import io.github.mangi.eta.fork.a2a.A2APart
import io.github.mangi.eta.fork.a2a.A2APeerPolicy
import io.github.mangi.eta.fork.a2a.A2ARole
import io.github.mangi.eta.fork.a2a.A2ATaskState
import io.github.mangi.eta.fork.a2a.A2ADatabase
import io.github.mangi.eta.fork.meeting.MeetingRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Executes durable task runs against the shared Runtime or a remote A2A peer. */
internal class DefaultAgentTaskRunner(
    private val context: Context,
) : AgentTaskRunner {
    private val appContext = context.applicationContext

    override suspend fun execute(request: AgentTaskExecutionRequest): AgentTaskExecutionResult =
        withContext(Dispatchers.IO) {
            try {
                when (request.kind) {
                    AgentTaskRunKind.CHAT -> executeRuntime(request)
                    AgentTaskRunKind.MEETING -> executeMeeting(request)
                    AgentTaskRunKind.A2A -> executeA2A(request)
                    AgentTaskRunKind.MAINTENANCE -> executeRuntime(request)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                AndroidAgentLogger.warn(
                    "Scheduled task execution failed: kind=${request.kind} type=${failure.safeLogType()}",
                )
                AgentTaskExecutionResult.Failed(
                    code = "RUNNER_FAILURE",
                    message = failure.message ?: failure.javaClass.simpleName,
                )
            }
        }

    private suspend fun executeRuntime(request: AgentTaskExecutionRequest): AgentTaskExecutionResult {
        val primary = AgentModelClient.loadConfig()
        val resolved = if (request.targetId.isBlank()) {
            RuntimeConfigRepository.subagentRuntimeConfig(primary, profileName = "default")
        } else {
            RuntimeConfigRepository.subagentRuntimeConfig(primary, profileName = request.targetId)
        }
        val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
        val result = client.run(
            request = AgentRuntimeWire.RunRequest(
                runId = request.runtimeRunId,
                prompt = request.resumePrompt(),
                config = resolved.config,
                images = emptyList(),
                handoff = AgentRuntimeWire.EntryHandoff(
                    id = request.runtimeRunId,
                    source = SCHEDULED_HANDOFF_SOURCE,
                    payload = request.definitionId,
                ),
                modelSessionId = "scheduled-task:${request.definitionId}",
            ),
            onEvent = { },
        )
        runCatching { client.ackResult(result.runId.ifBlank { request.runtimeRunId }) }
        return if (result.ok) {
            AgentTaskExecutionResult.Succeeded(result.runId.ifBlank { request.runtimeRunId })
        } else {
            AgentTaskExecutionResult.Failed(
                code = "RUNTIME_FAILED",
                message = result.error.orEmpty().ifBlank { "Agent Runtime returned no result" },
            )
        }
    }

    private suspend fun executeA2A(request: AgentTaskExecutionRequest): AgentTaskExecutionResult {
        val database = A2ADatabase.get(appContext)
        val dao = database.dao()
        val agent = dao.agent(request.targetId)
            ?: return AgentTaskExecutionResult.Failed("A2A_AGENT_MISSING", "A2A Agent 不存在")
        if (!agent.enabled) {
            return AgentTaskExecutionResult.Failed("A2A_AGENT_DISABLED", "A2A Agent 已停用")
        }
        val host = agent.endpointUrl.toHttpUrl().host
        val repository = A2AAgentRepository(
            context = appContext,
            client = A2AHttpClient(A2APeerPolicy(allowedHosts = setOf(host))),
            dao = dao,
        )
        val response = repository.sendMessage(
            agentId = agent.id,
            message = A2AMessage(
                messageId = request.runtimeRunId,
                role = A2ARole.USER,
                parts = listOf(A2APart(text = request.resumePrompt())),
            ),
            clientTaskId = request.runtimeRunId,
            returnImmediately = true,
        )
        response.task?.let { initialTask ->
            var task = initialTask
            for (attempt in 0 until 120) {
                if (task.status.state.isTerminal || task.status.state.isInterrupted) break
                delay(2_000L)
                if (AgentTaskDatabase.get(appContext).agentTaskDao().run(request.taskRunId)?.state ==
                    AgentTaskState.CANCELED
                ) {
                    runCatching { repository.cancelTask(agent.id, request.runtimeRunId) }
                    return AgentTaskExecutionResult.Failed(
                        code = "A2A_CANCELED",
                        message = "A2A task canceled locally",
                    )
                }
                task = repository.getTask(agent.id, request.runtimeRunId)
            }
            return when (task.status.state) {
                A2ATaskState.INPUT_REQUIRED -> AgentTaskExecutionResult.InputRequired(
                    task.status.message?.parts?.mapNotNull(A2APart::text)?.joinToString("\n")
                        .orEmpty().ifBlank { "远端 Agent 请求补充输入" },
                )
                A2ATaskState.AUTH_REQUIRED -> AgentTaskExecutionResult.AuthRequired(
                    task.status.message?.parts?.mapNotNull(A2APart::text)?.joinToString("\n")
                        .orEmpty().ifBlank { "远端 Agent 请求授权" },
                )
                A2ATaskState.FAILED, A2ATaskState.CANCELED, A2ATaskState.REJECTED ->
                    AgentTaskExecutionResult.Failed(
                        code = "A2A_${task.status.state.name}",
                        message = task.status.message?.parts?.mapNotNull(A2APart::text)
                            ?.joinToString("\n")?.ifBlank { null } ?: "A2A task failed",
                    )
                A2ATaskState.COMPLETED -> AgentTaskExecutionResult.Succeeded(request.runtimeRunId)
                else -> AgentTaskExecutionResult.Failed(
                    code = "A2A_NOT_FINISHED",
                    message = "A2A 任务在等待窗口内未完成：${task.status.state.name}",
                )
            }
        }
        return if (response.message != null) {
            AgentTaskExecutionResult.Succeeded(request.runtimeRunId)
        } else {
            AgentTaskExecutionResult.Failed("A2A_EMPTY_RESPONSE", "A2A Agent 返回空响应")
        }
    }

    private suspend fun executeMeeting(request: AgentTaskExecutionRequest): AgentTaskExecutionResult {
        val completed = MeetingRuntime.runTurn(
            context = appContext,
            roomId = request.targetId,
            rawText = request.resumePrompt(),
        )
        return if (completed) {
            AgentTaskExecutionResult.Succeeded("meeting:${request.targetId}:${request.runtimeRunId}")
        } else {
            AgentTaskExecutionResult.Failed(
                code = "MEETING_RUN_FAILED",
                message = "会议室不存在、已有本轮正在运行或主持人无法执行",
            )
        }
    }

    private companion object {
        const val SCHEDULED_HANDOFF_SOURCE = "scheduled_task"
    }

    private fun AgentTaskExecutionRequest.resumePrompt(): String =
        continuationInput?.takeIf(String::isNotBlank)?.let { input ->
            "$prompt\n\n<user_continuation>\n${input.take(12_000)}\n</user_continuation>"
        } ?: prompt
}
