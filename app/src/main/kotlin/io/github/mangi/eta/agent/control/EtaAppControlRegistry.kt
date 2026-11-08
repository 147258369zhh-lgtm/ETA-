package io.github.mangi.eta.agent.control

import android.content.Context
import io.github.mangi.eta.agent.prompt.PromptInjectionStore
import io.github.mangi.eta.agent.subagent.SubagentProfileCatalog
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.ModelFallbackSettings
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.AutoBackupStore
import io.github.mangi.eta.data.repository.LocalModelRepository
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.fork.learning.LearningRepository
import io.github.mangi.eta.fork.learning.LearningSettingsStore
import io.github.mangi.eta.fork.projects.ProjectDraft
import io.github.mangi.eta.fork.projects.ProjectRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONArray
import org.json.JSONObject

internal enum class EtaControlDanger {
    SAFE,
    CONFIRM,
}

internal data class EtaControlCapabilityInfo(
    val id: String,
    val title: String,
    val category: String,
    val summary: String,
    val danger: EtaControlDanger,
    val parameters: JSONObject,
    val canRead: Boolean,
)

internal sealed interface EtaAppControlEvent {
    data class Navigate(val routeKey: String) : EtaAppControlEvent
}

/** Runtime service and the app shell share the main process, so navigation stays in-process. */
internal object EtaAppControlEvents {
    private val channel = Channel<EtaAppControlEvent>(Channel.BUFFERED)
    val events: Flow<EtaAppControlEvent> = channel.receiveAsFlow()

    fun navigate(routeKey: String) {
        channel.trySend(EtaAppControlEvent.Navigate(routeKey))
    }
}

internal class EtaControlException(
    val code: String,
    override val message: String,
) : IllegalArgumentException(message)

/**
 * Stable, model-readable registry for operations owned by the Eta app shell.
 * Feature modules register typed capabilities here instead of growing one central dispatcher.
 */
internal object EtaAppControlRegistry {
    private data class Definition(
        val info: EtaControlCapabilityInfo,
        val read: (suspend (Context) -> JSONObject)? = null,
        val execute: suspend (Context, JSONObject) -> JSONObject,
    )

    private val lock = Any()
    private val definitions = linkedMapOf<String, Definition>()

    init {
        registerNavigation()
        registerSettings()
        registerReadOnlyState()
        registerModelState()
        registerLocalModels()
        registerMcpState()
        registerPromptState()
        registerSubagentState()
        registerProjectState()
        registerLearningState()
        registerBackupState()
        registerWorkspaceState()
    }

    fun list(category: String = ""): List<EtaControlCapabilityInfo> = synchronized(lock) {
        definitions.values
            .map(Definition::info)
            .filter { category.isBlank() || it.category.equals(category.trim(), ignoreCase = true) }
            .sortedWith(compareBy(EtaControlCapabilityInfo::category, EtaControlCapabilityInfo::title))
    }

    fun search(query: String): List<EtaControlCapabilityInfo> {
        val needle = query.trim()
        if (needle.isEmpty()) return list()
        return synchronized(lock) {
            definitions.values
                .map(Definition::info)
                .filter { info ->
                    listOf(info.id, info.title, info.category, info.summary)
                        .any { it.contains(needle, ignoreCase = true) }
                }
                .sortedBy(EtaControlCapabilityInfo::title)
        }
    }

    suspend fun get(context: Context, id: String): JSONObject {
        val definition = definition(id)
        val reader = definition.read ?: throw EtaControlException(
            "CAPABILITY_NOT_READABLE",
            "能力 ${definition.info.title} 只能执行，不能读取当前值",
        )
        return reader(context.applicationContext)
            .put("ok", true)
            .put("id", definition.info.id)
    }

    suspend fun execute(
        context: Context,
        id: String,
        parameters: JSONObject,
    ): JSONObject {
        val definition = definition(id)
        if (definition.info.danger == EtaControlDanger.CONFIRM) {
            val approved = EtaAppControlConfirmation.awaitApproval(definition.info, parameters)
            if (!approved) {
                throw EtaControlException("USER_CANCELLED", "用户已取消操作，未修改任何数据")
            }
        }
        return definition.execute(context.applicationContext, parameters)
            .put("ok", true)
            .put("id", definition.info.id)
    }

    private fun definition(id: String): Definition = synchronized(lock) {
        definitions[id.trim()] ?: throw EtaControlException(
            "UNKNOWN_CAPABILITY",
            "未知软件能力：${id.trim().ifBlank { "(empty)" }}",
        )
    }

    private fun register(definition: Definition) {
        val id = definition.info.id
        require(id.matches(Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+"))) {
            "Invalid app control capability id: $id"
        }
        synchronized(lock) {
            check(definitions.put(id, definition) == null) { "Duplicate app control capability: $id" }
        }
    }

    private fun registerReadOnly(
        id: String,
        title: String,
        category: String,
        summary: String,
        read: suspend (Context) -> JSONObject,
    ) {
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = id,
                    title = title,
                    category = category,
                    summary = summary,
                    danger = EtaControlDanger.SAFE,
                    parameters = JSONObject(),
                    canRead = true,
                ),
                read = read,
                execute = { _, _ ->
                    throw EtaControlException("CAPABILITY_READ_ONLY", "${title}只能读取")
                },
            ),
        )
    }

    private fun registerNavigation() {
        fun navigation(id: String, title: String, category: String, summary: String, routeKey: String) {
            register(
                Definition(
                    info = EtaControlCapabilityInfo(
                        id = id,
                        title = title,
                        category = category,
                        summary = summary,
                        danger = EtaControlDanger.SAFE,
                        parameters = JSONObject(),
                        canRead = false,
                    ),
                    execute = { _, _ ->
                        EtaAppControlEvents.navigate(routeKey)
                        JSONObject().put("routeKey", routeKey).put("opened", true)
                    },
                ),
            )
        }

        fun dynamicNavigation(
            id: String,
            title: String,
            category: String,
            summary: String,
            parameter: String,
            routeKey: (String) -> String,
        ) {
            register(
                Definition(
                    info = EtaControlCapabilityInfo(
                        id = id,
                        title = title,
                        category = category,
                        summary = summary,
                        danger = EtaControlDanger.SAFE,
                        parameters = JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject().put(parameter, JSONObject().put("type", "string")),
                            )
                            .put("required", JSONArray().put(parameter)),
                        canRead = false,
                    ),
                    execute = { _, parameters ->
                        val value = parameters.optString(parameter).trim()
                        require(value.isNotEmpty() && value.length <= 256 && value.none(Char::isISOControl)) {
                            "$parameter 无效"
                        }
                        val key = routeKey(value)
                        EtaAppControlEvents.navigate(key)
                        JSONObject().put("routeKey", key).put("opened", true)
                    },
                ),
            )
        }

        navigation("navigation.home", "返回对话", "导航", "打开主对话页面", "home")
        navigation("navigation.models", "模型提供商", "模型", "管理在线模型、备用模型和提供商", "models")
        navigation("navigation.local_models", "本地模型", "模型", "打开本地文本、视觉、语音和向量模型中心", "local_models")
        navigation("navigation.memory", "记忆与自学习", "智能", "查看分层记忆、整理候选与自学习策略", "memory")
        navigation("navigation.capabilities", "能力中心", "智能", "管理 Skill、MCP、连接器和工具边界", "capabilities")
        navigation("navigation.skills", "Skills", "智能", "管理已安装 Skill", "skills")
        navigation("navigation.mcp", "MCP 服务", "智能", "管理 MCP 服务及工具授权", "mcp")
        navigation("navigation.prompt_injection", "提示词注入", "智能", "管理全局、Agent、会话与会议室提示词", "prompt_injection")
        navigation("navigation.api_connectors", "REST API 连接", "智能", "管理 OAuth 与 REST API 外部能力", "api_connectors")
        navigation("navigation.automations", "自动任务", "自动化", "创建和查看自动任务及运行记录", "automations")
        navigation("navigation.subagents", "子 Agent", "智能", "管理子 Agent、角色指令和工具权限", "subagents")
        navigation("navigation.learning", "自学习策略", "智能", "管理经验提炼、验证和规则晋升", "learning")
        navigation("navigation.a2a", "A2A Agent", "自动化", "连接其他设备或 Agent", "a2a")
        navigation("navigation.metrics", "运行统计", "统计", "查看 Token、工具执行与运行轨迹", "metrics")
        navigation("navigation.permissions", "权限与边界", "系统", "检查无障碍、Root、悬浮窗和工具权限", "permissions")
        navigation("navigation.speech", "语音设置", "语音", "设置语音识别、合成与自动朗读", "speech")
        navigation("navigation.speech_recognition", "语音识别", "语音", "设置识别模型、唤醒词与声纹", "speech_recognition")
        navigation("navigation.speech_synthesis", "语音合成", "语音", "设置合成模型、音色与播放", "speech_synthesis")
        navigation("navigation.system_enhance", "系统增强", "系统", "管理接管、无障碍与系统增强能力", "system_enhance")
        navigation("navigation.characters", "角色", "智能", "管理角色、人设与剧情记忆", "characters")
        navigation("navigation.workspace", "项目文件", "文件", "打开项目文件与共享目录", "workspace")
        navigation("navigation.projects", "项目", "文件", "管理项目专属文件夹", "projects")
        navigation("navigation.shared_folders", "共享文件夹", "文件", "管理 Android 与 Linux 工作区共享目录", "shared_folders")
        navigation("navigation.backup", "数据备份", "系统", "导出、导入、自动备份与恢复", "backup")
        navigation("navigation.terminal", "终端", "系统", "打开设备或 Linux 终端", "terminal")
        navigation("navigation.settings", "设置", "系统", "打开全部软件设置", "settings")
        dynamicNavigation(
            id = "navigation.model_provider",
            title = "打开模型提供商",
            category = "模型",
            summary = "按提供商 ID 打开模型提供商详情",
            parameter = "providerId",
        ) { "model_provider:$it" }
        dynamicNavigation(
            id = "navigation.mcp_server",
            title = "打开 MCP 服务",
            category = "智能",
            summary = "按服务 ID 打开 MCP 详情与工具目录",
            parameter = "serverId",
        ) { "mcp_server:$it" }
        dynamicNavigation(
            id = "navigation.meeting_room",
            title = "打开会议室",
            category = "智能",
            summary = "按会议室 ID 打开对话空间",
            parameter = "roomId",
        ) { "meeting_room:$it" }
    }

    private fun registerSettings() {
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "settings.memory.enabled",
                    title = "记忆开关",
                    category = "设置",
                    summary = "读取或修改全局记忆注入与读写开关",
                    danger = EtaControlDanger.SAFE,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put(
                                "enabled",
                                JSONObject().put("type", "boolean").put("description", "新的记忆开关状态"),
                            ),
                        )
                        .put("required", org.json.JSONArray().put("enabled")),
                    canRead = true,
                ),
                read = {
                    JSONObject().put("enabled", SettingsDataStore.settings().memoryEnabled)
                },
                execute = { _, parameters ->
                    require(parameters.has("enabled")) { "缺少 enabled" }
                    val enabled = parameters.getBoolean("enabled")
                    SettingsDataStore.updateSettings { it.copy(memoryEnabled = enabled) }
                    JSONObject().put("enabled", enabled)
                },
            ),
        )
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "settings.memory.clear",
                    title = "清空全局记忆",
                    category = "设置",
                    summary = "删除全局长期记忆文件；项目、会话和会议室作用域不受影响",
                    danger = EtaControlDanger.CONFIRM,
                    parameters = JSONObject(),
                    canRead = false,
                ),
                execute = { _, _ ->
                    val snapshot = AgentMemoryRepository.replaceAll("")
                    JSONObject().put("cleared", true).put("revision", snapshot.revision)
                },
            ),
        )
    }

    private fun registerReadOnlyState() {
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "state.current_model",
                    title = "当前模型",
                    category = "状态",
                    summary = "读取当前默认模型与工具支持状态，不暴露密钥",
                    danger = EtaControlDanger.SAFE,
                    parameters = JSONObject(),
                    canRead = true,
                ),
                read = {
                    val config = RuntimeConfigRepository.currentRuntimeConfig()
                        ?: throw EtaControlException("MODEL_NOT_CONFIGURED", "尚未配置可用模型")
                    JSONObject()
                        .put("providerId", config.providerId)
                        .put("providerName", config.providerName)
                        .put("modelId", config.model)
                        .put("modelName", config.modelDisplayName.ifBlank { config.model })
                        .put("supportsTools", config.supportsTools)
                        .put("contextWindow", config.contextWindow ?: JSONObject.NULL)
                },
                execute = { _, _ ->
                    throw EtaControlException("CAPABILITY_READ_ONLY", "当前模型状态只能读取")
                },
            ),
        )
    }

    private fun registerModelState() {
        registerReadOnly(
            id = "state.models",
            title = "模型目录",
            category = "模型",
            summary = "读取已配置提供商及模型能力，不暴露 API 密钥",
        ) { _ ->
            ProviderRepository.ensureBuiltInsMerged()
            val providers = ProviderRepository.allProviders()
            JSONObject().put(
                "providers",
                JSONArray().also { providerArray ->
                    providers.forEach { provider ->
                        providerArray.put(
                            JSONObject()
                                .put("id", provider.id)
                                .put("name", provider.name)
                                .put("enabled", provider.isEnabled)
                                .put("builtIn", provider.isBuiltIn)
                                .put("sourceType", provider.sourceType)
                                .put(
                                    "models",
                                    JSONArray().also { modelArray ->
                                        provider.models.sortedBy { it.sortOrder }.forEach { model ->
                                            modelArray.put(
                                                JSONObject()
                                                    .put("id", model.id)
                                                    .put("modelId", model.modelId)
                                                    .put("name", model.displayName)
                                                    .put("enabled", model.isEnabled)
                                                    .put("contextWindow", model.contextWindow ?: JSONObject.NULL)
                                                    .put("contextWindowOverride", model.contextWindowOverride ?: JSONObject.NULL)
                                                    .put("supportsTools", model.supportsTools)
                                                    .put("supportsVision", model.supportsVision)
                                                    .put("supportsReasoning", model.supportsReasoning),
                                            )
                                        }
                                    },
                                ),
                        )
                    }
                },
            )
        }
        registerReadOnly(
            id = "state.model_fallback",
            title = "备用模型",
            category = "模型",
            summary = "读取备用模型开关、提供商和模型",
        ) { _ ->
            RuntimeConfigRepository.modelFallbackSettings().toJson()
        }
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "settings.model_fallback",
                    title = "设置备用模型",
                    category = "模型",
                    summary = "修改主模型临时失败时使用的备用提供商与模型",
                    danger = EtaControlDanger.SAFE,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("enabled", JSONObject().put("type", "boolean"))
                                .put(
                                    "providerId",
                                    JSONObject().put("type", JSONArray().put("string").put("null")),
                                )
                                .put(
                                    "modelId",
                                    JSONObject().put("type", JSONArray().put("string").put("null")),
                                ),
                        )
                        .put("required", JSONArray().put("enabled")),
                    canRead = true,
                ),
                read = { RuntimeConfigRepository.modelFallbackSettings().toJson() },
                execute = { _, parameters ->
                    require(parameters.has("enabled")) { "缺少 enabled" }
                    val enabled = parameters.getBoolean("enabled")
                    val modelId = parameters.optString("modelId").trim().takeIf(String::isNotEmpty)
                    val providerId = parameters.optString("providerId").trim().takeIf(String::isNotEmpty)
                    if (enabled) {
                        requireNotNull(modelId) { "启用备用模型时必须指定 modelId" }
                        ProviderRepository.ensureBuiltInsMerged()
                        requireNotNull(ProviderRepository.allProviders().enabledModel(modelId, providerId)) {
                            "备用模型不存在、未启用或不属于指定提供商"
                        }
                    }
                    val settings = ModelFallbackSettings(
                        enabled = enabled,
                        modelId = modelId,
                        providerId = providerId,
                    ).normalized()
                    RuntimeConfigRepository.setModelFallbackSettings(settings)
                    settings.toJson()
                },
            ),
        )
    }

    private fun registerLocalModels() {
        registerReadOnly(
            id = "state.local_models",
            title = "本地模型",
            category = "模型",
            summary = "读取本地文本、视觉、语音与向量模型的安装、绑定和运行状态",
        ) { context ->
            LocalModelRepository.init(context)
            val runtime = LocalModelRepository.runtime.value
            val download = LocalModelRepository.download.value
            JSONObject()
                .put(
                    "models",
                    JSONArray().also { array ->
                        LocalModelRepository.models.value.forEach { model ->
                            array.put(
                                JSONObject()
                                    .put("id", model.id)
                                    .put("name", model.displayName)
                                    .put("format", model.format.name)
                                    .put("runtime", model.runtime.name)
                                    .put("sizeBytes", model.sizeBytes)
                                    .put("inputModalities", JSONArray(model.inputModalities))
                                    .put("outputModalities", JSONArray(model.outputModalities))
                                    .put("supportsTools", model.supportsTools)
                                    .put("supportsVision", model.supportsVision)
                                    .put("boundProviderId", model.boundProviderId ?: JSONObject.NULL)
                                    .put("boundModelSelectionId", model.boundModelSelectionId ?: JSONObject.NULL)
                                    .put("contextWindow", model.contextWindow)
                                    .put("backend", model.runtimeSettings.backend.name),
                            )
                        }
                    },
                )
                .put(
                    "runtime",
                    JSONObject()
                        .put("recordId", runtime.recordId ?: JSONObject.NULL)
                        .put("state", runtime.state.name)
                        .put("backend", runtime.backend)
                        .put("message", runtime.message),
                )
                .put(
                    "download",
                    JSONObject()
                        .put("id", download.id ?: JSONObject.NULL)
                        .put("name", download.displayName)
                        .put("stage", download.stage?.name ?: JSONObject.NULL)
                        .put("bytesDownloaded", download.bytesDownloaded)
                        .put("totalBytes", download.totalBytes ?: JSONObject.NULL),
                )
        }
    }

    private fun registerMcpState() {
        registerReadOnly(
            id = "state.mcp_servers",
            title = "MCP 服务",
            category = "智能",
            summary = "读取 MCP 服务、启用状态、工具目录与授权状态，不暴露凭据",
        ) { context ->
            McpServerRepository.init(context)
            JSONObject().put(
                "servers",
                JSONArray().also { array ->
                    McpServerRepository.servers().forEach { server ->
                        array.put(
                            JSONObject()
                                .put("id", server.id)
                                .put("name", server.name)
                                .put("enabled", server.enabled)
                                .put("protocolMode", server.protocolMode)
                                .put("authorizationType", server.authorizationType)
                                .put("activeToolCount", server.activeTools.size)
                                .put("toolCount", server.tools.size)
                                .put(
                                    "tools",
                                    JSONArray(server.tools.map { tool ->
                                        JSONObject()
                                            .put("name", tool.name)
                                            .put("title", tool.title)
                                            .put("enabled", tool.name in server.enabledToolNames)
                                            .put("readOnly", tool.readOnlyHint ?: JSONObject.NULL)
                                            .put("destructive", tool.destructiveHint ?: JSONObject.NULL)
                                    }),
                                ),
                        )
                    }
                },
            )
        }
    }

    private fun registerPromptState() {
        registerReadOnly(
            id = "state.prompt_templates",
            title = "提示词模板",
            category = "智能",
            summary = "读取提示词模板、作用位置及各会话范围绑定",
        ) { _ ->
            JSONObject()
                .put(
                    "templates",
                    JSONArray(PromptInjectionStore.templates().map { template ->
                        JSONObject()
                            .put("id", template.id)
                            .put("name", template.name)
                            .put("description", template.description)
                            .put("enabled", template.enabled)
                            .put("source", template.source)
                            .put("characterCount", template.characterCount)
                            .put(
                                "blocks",
                                JSONArray(template.blocks.sortedWith(io.github.mangi.eta.agent.prompt.PromptTemplate.BLOCK_ORDER).map {
                                    JSONObject()
                                        .put("title", it.title)
                                        .put("position", it.position.wireValue)
                                        .put("enabled", it.enabled)
                                }),
                            )
                    }),
                )
                .put(
                    "bindings",
                    JSONArray(PromptInjectionStore.bindings().map { binding ->
                        binding.toJson()
                    }),
                )
        }
    }

    private fun registerSubagentState() {
        registerReadOnly(
            id = "state.subagents",
            title = "子 Agent",
            category = "智能",
            summary = "读取内置与自定义子 Agent、角色指令和工具边界",
        ) { _ ->
            JSONObject().put(
                "subagents",
                JSONArray(SubagentProfileCatalog.listAll().take(100).map { profile ->
                    JSONObject()
                        .put("name", profile.name)
                        .put("summary", profile.summary)
                        .put("whenToUse", profile.whenToUse)
                        .put("builtin", profile.builtin)
                        .put("override", profile.override)
                        .put("toolHighlights", JSONArray(profile.toolHighlights))
                        .put(
                            "explicitTools",
                            profile.explicitTools?.let { JSONArray(it.toList()) } ?: JSONObject.NULL,
                        )
                        .put("disallowedTools", JSONArray(profile.disallowedTools.toList()))
                        .put("roleInstruction", profile.roleInstruction.take(4_000))
                }),
            )
        }
    }

    private fun registerProjectState() {
        registerReadOnly(
            id = "state.projects",
            title = "项目文件夹",
            category = "文件",
            summary = "读取项目名称、受管目录、归档状态及会话或会议室绑定",
        ) { context ->
            val projects = ProjectRepository.projects(context).first()
            JSONObject().put(
                "projects",
                JSONArray().also { array ->
                    projects.forEach { project ->
                        array.put(
                            JSONObject()
                                .put("id", project.id)
                                .put("name", project.name)
                                .put("providerId", project.providerId)
                                .put("rootDisplayName", project.rootDisplayName)
                                .put("source", project.source)
                                .put("archived", project.archivedAt != null)
                                .put("updatedAt", project.updatedAt),
                        )
                    }
                },
            )
        }
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "project.create",
                    title = "创建项目",
                    category = "文件",
                    summary = "把已获准访问的目录登记为项目专属文件夹",
                    danger = EtaControlDanger.SAFE,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("name", JSONObject().put("type", "string"))
                                .put("providerId", JSONObject().put("type", "string"))
                                .put("rootIdentityKey", JSONObject().put("type", "string"))
                                .put("rootDisplayName", JSONObject().put("type", "string"))
                                .put("source", JSONObject().put("type", "string")),
                        )
                        .put(
                            "required",
                            JSONArray(listOf("name", "providerId", "rootIdentityKey", "rootDisplayName", "source")),
                        ),
                    canRead = false,
                ),
                execute = { context, parameters ->
                    val id = ProjectRepository.create(
                        context,
                        ProjectDraft(
                            name = parameters.getString("name"),
                            providerId = parameters.getString("providerId"),
                            rootIdentityKey = parameters.getString("rootIdentityKey"),
                            rootDisplayName = parameters.getString("rootDisplayName"),
                            source = parameters.getString("source"),
                        ),
                    )
                    JSONObject().put("projectId", id)
                },
            ),
        )
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "project.rename",
                    title = "重命名项目",
                    category = "文件",
                    summary = "修改项目显示名称，不移动项目目录",
                    danger = EtaControlDanger.SAFE,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("projectId", JSONObject().put("type", "string"))
                                .put("name", JSONObject().put("type", "string")),
                        )
                        .put("required", JSONArray(listOf("projectId", "name"))),
                    canRead = false,
                ),
                execute = { context, parameters ->
                    val projectId = parameters.getString("projectId")
                    val name = parameters.getString("name")
                    ProjectRepository.rename(context, projectId, name)
                    JSONObject().put("projectId", projectId).put("name", name.trim())
                },
            ),
        )
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "project.archive",
                    title = "归档项目",
                    category = "文件",
                    summary = "归档或恢复项目及其会话上下文入口，不删除项目文件",
                    danger = EtaControlDanger.SAFE,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put("projectId", JSONObject().put("type", "string"))
                                .put("archived", JSONObject().put("type", "boolean")),
                        )
                        .put("required", JSONArray(listOf("projectId", "archived"))),
                    canRead = false,
                ),
                execute = { context, parameters ->
                    val projectId = parameters.getString("projectId")
                    val archived = parameters.getBoolean("archived")
                    ProjectRepository.setArchived(context, projectId, archived)
                    JSONObject().put("projectId", projectId).put("archived", archived)
                },
            ),
        )
        register(
            Definition(
                info = EtaControlCapabilityInfo(
                    id = "project.delete",
                    title = "移除项目",
                    category = "文件",
                    summary = "从 Eta 移除项目登记及其会话、会议室绑定；不会删除磁盘上的原始文件夹",
                    danger = EtaControlDanger.CONFIRM,
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject().put("projectId", JSONObject().put("type", "string")),
                        )
                        .put("required", JSONArray().put("projectId")),
                    canRead = false,
                ),
                execute = { context, parameters ->
                    val projectId = parameters.getString("projectId")
                    ProjectRepository.delete(context, projectId)
                    JSONObject().put("projectId", projectId).put("registered", false)
                },
            ),
        )
    }

    private fun registerLearningState() {
        registerReadOnly(
            id = "state.learning",
            title = "自学习状态",
            category = "智能",
            summary = "读取自学习开关、候选规则、生效规则和冲突状态",
        ) { context ->
            val snapshot = LearningRepository.get(context).snapshot()
            JSONObject()
                .put("enabled", LearningSettingsStore.isEnabled(context))
                .put("automaticCaptureEnabled", LearningSettingsStore.automaticCaptureEnabled(context))
                .put("automaticPromotionEnabled", LearningSettingsStore.automaticPromotionEnabled(context))
                .put("candidateCount", snapshot.candidates.size)
                .put("ruleCount", snapshot.rules.size)
                .put(
                    "candidates",
                    JSONArray(snapshot.candidates.take(100).map { candidate ->
                        JSONObject()
                            .put("id", candidate.id)
                            .put("kind", candidate.kind.name)
                            .put("title", candidate.title)
                            .put("status", candidate.status.name)
                            .put("confidence", candidate.confidence)
                            .put("conflictCount", candidate.conflicts.size)
                    }),
                )
                .put(
                    "rules",
                    JSONArray(snapshot.rules.take(100).map { rule ->
                        JSONObject()
                            .put("id", rule.id)
                            .put("kind", rule.kind.name)
                            .put("title", rule.title)
                            .put("active", rule.active)
                            .put("pinned", rule.pinned)
                            .put("version", rule.currentVersion)
                    }),
                )
        }
    }

    private fun registerBackupState() {
        registerReadOnly(
            id = "state.backup",
            title = "备份状态",
            category = "系统",
            summary = "读取自动备份时间、格式、内容范围、最近状态与下次条件",
        ) { context ->
            val settings = AutoBackupStore.read(context)
            JSONObject()
                .put("enabled", settings.enabled)
                .put("configured", settings.tree.isNotBlank())
                .put("intervalDays", settings.days)
                .put("hour", settings.hour)
                .put("keepCount", settings.keep)
                .put("chargingOnly", settings.chargingOnly)
                .put("format", settings.options.format.name)
                .put("compression", settings.options.compression.name)
                .put("includeAppFiles", settings.options.includeAppFiles)
                .put("contents", JSONArray(settings.options.contents.map { it.name }))
                .put("lastSuccessAt", AutoBackupStore.lastSuccess(context))
                .put("status", AutoBackupStore.status(context))
        }
    }

    private fun registerWorkspaceState() {
        registerReadOnly(
            id = "state.shared_folders",
            title = "共享文件夹",
            category = "文件",
            summary = "读取 Android 与 Linux 工作区之间已挂载的文件夹",
        ) { _ ->
            JSONObject().put(
                "folders",
                JSONArray(SharedFolderMounts.current().map { mount ->
                    JSONObject()
                        .put("name", mount.name)
                        .put("sourcePath", mount.sourcePath)
                        .put("linuxPath", "${SharedFolderMounts.LINUX_MOUNTS_ROOT}/${mount.name}")
                }),
            )
        }
    }

    private fun ModelFallbackSettings.toJson(): JSONObject = JSONObject()
        .put("enabled", enabled)
        .put("providerId", providerId ?: JSONObject.NULL)
        .put("modelId", modelId ?: JSONObject.NULL)
        .put("maxAttemptsPerRun", maxAttemptsPerRun)
}
