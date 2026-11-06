package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import java.util.UUID
import org.json.JSONObject

/**
 * A no-tools safety net for failures in the cross-process Runtime handoff.
 * Model/provider failures intentionally do not enter this path.
 */
internal object AgentRuntimeLocalFallback {
    fun shouldUse(error: String?): Boolean {
        val value = error.orEmpty()
        return value.contains("Agent Runtime 服务绑定失败") ||
            value.contains("无法启动后台执行服务") ||
            value.contains("Agent Runtime 服务连接已断开") ||
            value.contains("Agent Runtime 无法准备请求") ||
            value.contains("Agent Runtime 请求发送失败")
    }

    fun run(
        request: AgentRuntimeWire.RunRequest,
        onEvent: (AgentEvent) -> Unit,
    ): AgentRuntimeWire.RunResult {
        val safeConfig = request.config.copy(
            systemPrompt = request.config.systemPrompt + "\n\n" +
                "The cross-process Agent Runtime is temporarily unavailable. " +
                "Answer this turn directly without tools, device control, files, network browsing, memory writes, or Subagent calls. " +
                "Do not claim that any tool was used.",
            terminalTools = false,
            browserTools = false,
            deviceDirectTools = false,
            deviceSensitiveReadTools = false,
            deviceSensitiveActionTools = false,
            subagentTools = false,
            supportsSearchSuggestions = false,
        )
        return runCatching {
            val response = AgentModelClient.complete(
                config = safeConfig,
                prompt = request.prompt,
                images = request.images,
                history = request.history,
                toolExecutor = AgentModelClient.ToolExecutor { call ->
                    AgentModelClient.ToolResult(
                        content = JSONObject()
                            .put("ok", false)
                            .put("code", "LOCAL_FALLBACK_TOOL_DISABLED")
                            .put("message", "Tool ${call.name} is unavailable during local fallback")
                            .toString(),
                    )
                },
                runController = AgentRunController(),
                skillContext = SkillContext.EMPTY,
                memoryContext = AgentMemoryContext.DISABLED,
                capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
                sessionId = request.effectiveModelSessionId,
                operationId = request.runId,
                initialUserMessageId = "user-${request.runId}",
                onEvent = onEvent,
            )
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = true,
                content = response.content,
                reasoningContent = response.reasoningContent,
                transcript = response.transcript,
                contextSnapshot = response.contextSnapshot,
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        }.getOrElse { failure ->
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = false,
                content = "",
                error = "Runtime 交接失败，进程内回复也失败：${failure.message ?: failure.javaClass.simpleName}",
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        }
    }
}
