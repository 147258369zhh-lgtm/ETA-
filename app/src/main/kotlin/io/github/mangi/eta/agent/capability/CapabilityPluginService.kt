package io.github.mangi.eta.agent.capability

import android.content.Context
import io.github.mangi.eta.agent.mcp.McpServerManager
import io.github.mangi.eta.agent.mcp.validateMcpEndpoint
import io.github.mangi.eta.agent.skill.SkillArchiveInspectionResult
import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.agent.skill.SkillInstallResult
import io.github.mangi.eta.agent.skill.SkillProvenance
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.repository.McpServerRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Installs a bounded plugin bundle after inspecting every contained capability. */
internal class CapabilityPluginService(context: Context) {
    private val appContext = context.applicationContext
    private val installer = SkillRuntime.createPackageInstaller(appContext)
    private val indexService = SkillRuntime.createIndexService(appContext)
    private val apiStore = ApiConnectorStore(appContext)
    private val apiService = ApiConnectorService(appContext)
    private val pluginStore = CapabilityPluginStore(appContext)
    private val journalStore = CapabilityPluginInstallJournal(appContext)
    private val provenanceStore = SkillRuntime.createProvenanceStore(appContext)

    fun inspect(openStream: () -> InputStream): CapabilityPluginPreview {
        val operation = File.createTempFile("eta-plugin-inspect-", "", appContext.cacheDir)
        operation.delete()
        return try {
            CapabilityPluginArchive.extract(openStream, operation)
            val root = CapabilityPluginArchive.contentRoot(operation)
            val manifestFile = listOf("plugin.json", "eta-plugin.json")
                .map { File(root, it) }
                .firstOrNull(File::isFile)
                ?: throw CapabilityPluginException("插件根目录缺少 plugin.json")
            if (manifestFile.length() > MAX_MANIFEST_BYTES) {
                throw CapabilityPluginException("plugin.json 超过 128 KiB")
            }
            val manifest = parseManifest(manifestFile.readText(Charsets.UTF_8))
            val candidates = when (val result = installer.inspectRepositoryZip(openStream)) {
                is SkillArchiveInspectionResult.Success -> result.candidates
                is SkillArchiveInspectionResult.Failure -> throw CapabilityPluginException(result.error.message)
            }
            val requestedSkills = manifest.skills.ifEmpty { candidates.map { it.relativePath } }
            val candidatePaths = candidates.mapTo(mutableSetOf()) { it.relativePath }
            val missing = requestedSkills.filterNot(candidatePaths::contains)
            if (missing.isNotEmpty()) {
                throw CapabilityPluginException("插件声明的 Skill 不存在：${missing.joinToString().take(300)}")
            }
            if (requestedSkills.size > MAX_SKILLS) {
                throw CapabilityPluginException("插件最多包含 $MAX_SKILLS 个 Skill")
            }
            val selectedCandidates = candidates.filter { it.relativePath in requestedSkills }
            val apiCount = manifest.apis.sumOf { declaration ->
                parseApiConnector(root, declaration).actions.size
            }
            CapabilityPluginPreview(
                manifest = manifest,
                skillPaths = requestedSkills.distinct(),
                plannedSkillIds = selectedCandidates.map { it.id }.distinct(),
                apiOperationCount = apiCount,
                warnings = buildList {
                    if (manifest.mcpServers.any { it.authorizationType == McpAuthorizationType.NONE }) {
                        add("无需认证的 MCP 连接将在安装时验证；所有工具默认关闭")
                    }
                    if (manifest.apis.isNotEmpty()) add("REST API 操作默认关闭，需要逐项授权")
                    if (manifest.apis.any { it.authType != ApiConnectorAuthType.NONE } ||
                        manifest.mcpServers.any { it.authorizationType == McpAuthorizationType.BEARER }
                    ) {
                        add("能力包不会携带令牌；安装后需要在连接详情中配置认证")
                    }
                    if (requestedSkills.isEmpty()) add("包内没有可安装的 Skill")
                },
            )
        } finally {
            CapabilityPluginArchive.deleteTree(operation)
        }
    }

    suspend fun install(
        openStream: () -> InputStream,
    ): CapabilityPluginInstallOutcome {
        if (!recoverInterruptedInstall()) {
            return CapabilityPluginInstallOutcome.Failure(
                "上一次能力包安装尚未完整回滚，请稍后重试或查看应用日志",
                recoveryRequired = true,
            )
        }
        val preview = runCatching { inspect(openStream) }.getOrElse {
            return CapabilityPluginInstallOutcome.Failure(it.message ?: "插件校验失败")
        }
        val existingPlugins = pluginStore.load()
        if (existingPlugins.any { it.name.equals(preview.manifest.name, ignoreCase = true) }) {
            return CapabilityPluginInstallOutcome.Failure("同名能力包已安装，请先卸载旧版本")
        }
        val operation = File.createTempFile("eta-plugin-install-", "", appContext.cacheDir)
        operation.delete()
        var installedSkills: List<io.github.mangi.eta.agent.skill.InstalledSkill> = emptyList()
        var journal: CapabilityPluginInstallTransaction? = null
        try {
            CapabilityPluginArchive.extract(openStream, operation)
            val root = CapabilityPluginArchive.contentRoot(operation)
            val connectors = preview.manifest.apis.map { parseApiConnector(root, it) }
            connectors.forEach(apiStore::validate)
            val existingSkillIds = indexService.listSkillsForManagement(forceRefresh = true)
                .asSequence()
                .filter(SkillIndexEntry::installed)
                .map { it.id }
                .toSet()
            val skillCollision = preview.plannedSkillIds.firstOrNull { it in existingSkillIds }
            if (skillCollision != null) {
                throw CapabilityPluginException("插件中的 Skill 与现有能力冲突：$skillCollision")
            }
            val existingServers = McpServerRepository.servers()
            val stagedMcp = preview.manifest.mcpServers.map { declaration ->
                if (existingServers.any { it.url.equals(declaration.url, ignoreCase = true) }) {
                    throw CapabilityPluginException("MCP 地址已存在：${declaration.url}")
                }
                val draft = McpServerSetting(
                    id = UUID.randomUUID().toString(),
                    name = declaration.name,
                    url = declaration.url,
                    enabled = true,
                    protocolMode = McpProtocolMode.AUTO,
                    authorizationType = declaration.authorizationType,
                )
                if (declaration.authorizationType == McpAuthorizationType.NONE) {
                    McpServerManager.discover(draft, null)
                } else {
                    draft
                }
            }
            val now = System.currentTimeMillis()
            val pluginRecordId = "plugin-" + UUID.randomUUID().toString().lowercase(Locale.ROOT)
            journal = CapabilityPluginInstallTransaction(
                pluginName = preview.manifest.name,
                version = preview.manifest.version,
                pluginRecordId = pluginRecordId,
                skillIds = preview.plannedSkillIds,
                apiConnectorIds = connectors.map(ApiConnectorDefinition::id),
                mcpServerIds = stagedMcp.map(McpServerSetting::id),
                startedAt = now,
            ).also(journalStore::write)
            if (preview.skillPaths.isNotEmpty()) {
                val result = installer.installRepositoryZip(
                        openStream = openStream,
                        selectedPaths = preview.skillPaths,
                    )
                when (result) {
                    is SkillInstallResult.Success -> installedSkills = result.installed
                    is SkillInstallResult.Conflict -> throw CapabilityPluginException(
                        "插件中的 Skill 与现有能力冲突：${result.conflicts.joinToString { it.id }}",
                    )
                    is SkillInstallResult.Failure -> throw CapabilityPluginException(result.error.message)
                }
            }
            if (connectors.isNotEmpty()) {
                apiStore.save(apiStore.load() + connectors)
            }
            stagedMcp.forEach { server ->
                McpServerRepository.add(server, bearerToken = "")
            }
            val pluginRecord = InstalledCapabilityPlugin(
                id = pluginRecordId,
                name = preview.manifest.name,
                version = preview.manifest.version,
                description = preview.manifest.description,
                skillIds = installedSkills.map { it.id },
                apiConnectorIds = journal.apiConnectorIds,
                mcpServerIds = journal.mcpServerIds,
                installedAt = now,
            )
            pluginStore.upsert(pluginRecord)
            if (installedSkills.isNotEmpty()) {
                provenanceStore.recordAll(
                    installedSkills.map { skill ->
                        SkillProvenance(
                            skillId = skill.id,
                            sourceId = pluginRecord.id,
                            sourceName = preview.manifest.name,
                            sourceType = "plugin",
                            version = preview.manifest.version,
                            installedAt = now,
                        )
                    },
                )
            }
            indexService.listSkillsForManagement(forceRefresh = true)
            journalStore.clear()
            return CapabilityPluginInstallOutcome.Success(
                pluginName = preview.manifest.name,
                installedSkills = installedSkills.size,
                installedApis = pluginRecord.apiConnectorIds.size,
                installedMcpServers = pluginRecord.mcpServerIds.size,
            )
        } catch (failure: Throwable) {
            val activeJournal = journal
            val rollbackComplete = activeJournal == null || rollback(activeJournal).also { complete ->
                if (complete) journalStore.clear()
            }
            return CapabilityPluginInstallOutcome.Failure(
                failure.message?.lineSequence()?.firstOrNull()?.take(500) ?: "插件安装失败",
                recoveryRequired = !rollbackComplete,
            )
        } finally {
            CapabilityPluginArchive.deleteTree(operation)
        }
    }

    suspend fun uninstall(pluginId: String): CapabilityPluginUninstallOutcome {
        if (!recoverInterruptedInstall()) {
            return CapabilityPluginUninstallOutcome.Failure(
                "存在未完成的安装事务，恢复后才能继续卸载",
                recoveryRequired = true,
            )
        }
        val plugin = pluginStore.load().firstOrNull { it.id == pluginId }
            ?: return CapabilityPluginUninstallOutcome.Failure("能力包记录不存在")
        val failures = mutableListOf<String>()
        val installedSkillIds = runCatching {
            indexService.listSkillsForManagement(forceRefresh = true)
                .asSequence()
                .filter(SkillIndexEntry::installed)
                .map(SkillIndexEntry::id)
                .toSet()
        }.getOrElse {
            failures += "无法读取已安装 Skill"
            emptySet()
        }
        plugin.skillIds.filter(installedSkillIds::contains).forEach { skillId ->
            if (!runCatching { indexService.deleteSkill(skillId) }.getOrDefault(false)) {
                failures += "Skill $skillId 无法删除"
            }
        }
        runCatching { apiService.deleteMany(plugin.apiConnectorIds) }
            .onFailure { failures += "API 连接和凭据删除失败" }
        val existingMcpIds = runCatching { McpServerRepository.servers().mapTo(mutableSetOf()) { it.id } }
            .getOrElse {
                failures += "无法读取 MCP 连接"
                emptySet()
            }
        plugin.mcpServerIds.filter(existingMcpIds::contains).forEach { serverId ->
            runCatching { McpServerRepository.delete(serverId) }
                .onFailure { failures += "MCP 连接 $serverId 删除失败" }
        }
        if (failures.isNotEmpty()) {
            return CapabilityPluginUninstallOutcome.Failure(
                failures.joinToString("；").take(500),
                recoveryRequired = true,
            )
        }
        pluginStore.delete(plugin.id)
        return CapabilityPluginUninstallOutcome.Success
    }

    fun installedPlugins(): List<InstalledCapabilityPlugin> = pluginStore.load()

    suspend fun recoverInterruptedInstall(): Boolean {
        val journal = journalStore.load() ?: return true
        return rollback(journal).also { complete ->
            if (complete) journalStore.clear()
        }
    }

    private suspend fun rollback(journal: CapabilityPluginInstallTransaction): Boolean {
        var complete = true
        if (runCatching { pluginStore.delete(journal.pluginRecordId) }.isFailure) complete = false
        journal.mcpServerIds.forEach { id ->
            if (runCatching { McpServerRepository.delete(id) }.isFailure) complete = false
        }
        if (runCatching { apiService.deleteMany(journal.apiConnectorIds) }.isFailure) complete = false
        val currentSkills = runCatching {
            indexService.listSkillsForManagement(forceRefresh = true)
                .asSequence()
                .filter(SkillIndexEntry::installed)
                .map { it.id }
                .toSet()
        }.getOrElse {
            complete = false
            emptySet()
        }
        journal.skillIds.forEach { id ->
            if (id !in currentSkills) {
                runCatching { provenanceStore.remove(id) }.onFailure { complete = false }
                return@forEach
            }
            if (!runCatching { indexService.deleteSkill(id) }.getOrDefault(false)) complete = false
            runCatching { provenanceStore.remove(id) }
                .onFailure { complete = false }
        }
        return complete
    }

    private fun parseManifest(raw: String): CapabilityPluginManifest {
        val root = runCatching { JSONObject(raw) }
            .getOrElse { throw CapabilityPluginException("plugin.json 不是有效 JSON") }
        if (root.optString("schema") != PLUGIN_SCHEMA) {
            throw CapabilityPluginException("不支持的插件格式，需要 $PLUGIN_SCHEMA")
        }
        val name = requiredString(root, "name", 80)
        val version = requiredString(root, "version", 64)
        val description = root.optString("description").trim().take(1_000)
        val author = root.optString("author").trim().take(120)
        val skills = root.optJSONArray("skills").strings().map(::safeRelativePath)
        if (skills.size > MAX_SKILLS) throw CapabilityPluginException("插件最多声明 $MAX_SKILLS 个 Skill")
        val apis = root.optJSONArray("apis").objectsOrStrings().map { value ->
            when (value) {
                is String -> CapabilityPluginApiDeclaration(
                    name = File(value).nameWithoutExtension,
                    documentPath = safeRelativePath(value),
                )
                is JSONObject -> CapabilityPluginApiDeclaration(
                    name = value.optString("name").trim().take(80),
                    documentPath = safeRelativePath(value.optString("document").ifBlank { value.optString("path") }),
                    authType = pluginApiAuthType(value.optString("auth")),
                    authHeaderName = value.optString("authHeaderName", "X-API-Key").trim(),
                )
                else -> throw CapabilityPluginException("API 声明格式无效")
            }
        }
        if (apis.size > MAX_APIS || apis.distinctBy { it.documentPath }.size != apis.size) {
            throw CapabilityPluginException("API 文档声明无效或重复")
        }
        val mcpServers = root.optJSONArray("mcpServers").objectsOrStrings().map { value ->
            val item = value as? JSONObject ?: throw CapabilityPluginException("MCP 声明格式无效")
            val url = try {
                validateMcpEndpoint(item.optString("url")).toString()
            } catch (failure: Throwable) {
                throw CapabilityPluginException("MCP 地址无效：${failure.message.orEmpty().take(160)}")
            }
            CapabilityPluginMcpDeclaration(
                name = item.optString("name").trim().take(80).ifBlank { "MCP 连接" },
                url = url,
                authorizationType = when (item.optString("authorization", "none").trim().lowercase(Locale.ROOT)) {
                    McpAuthorizationType.NONE -> McpAuthorizationType.NONE
                    McpAuthorizationType.BEARER -> McpAuthorizationType.BEARER
                    else -> throw CapabilityPluginException("MCP 声明只支持 none 或 bearer 认证")
                },
            )
        }
        if (mcpServers.size > MAX_MCP_SERVERS) {
            throw CapabilityPluginException("插件最多声明 $MAX_MCP_SERVERS 个 MCP 连接")
        }
        return CapabilityPluginManifest(
            name = name,
            version = version,
            description = description,
            author = author,
            skills = skills,
            apis = apis,
            mcpServers = mcpServers,
            permissions = root.optJSONArray("permissions").strings().map { it.take(120) }.take(32),
        )
    }

    private fun parseApiConnector(
        root: File,
        declaration: CapabilityPluginApiDeclaration,
    ): ApiConnectorDefinition {
        val file = resolveContained(root, declaration.documentPath)
        if (!file.isFile) throw CapabilityPluginException("API 文档不存在：${declaration.documentPath}")
        if (file.length() > MAX_API_DOCUMENT_BYTES) throw CapabilityPluginException("API 文档超过 2 MiB")
        val text = file.inputStream().buffered().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_API_DOCUMENT_BYTES) throw CapabilityPluginException("API 文档超过 2 MiB")
                output.write(buffer, 0, count)
            }
            output.toByteArray().toString(Charsets.UTF_8)
        }
        return OpenApiConnectorParser.parse(
            specification = text,
            sourceUrl = "https://plugin.invalid/${declaration.documentPath}",
            requestedName = declaration.name,
            connectorId = "api-" + UUID.randomUUID().toString().lowercase(Locale.ROOT),
            authType = declaration.authType,
            authHeaderName = declaration.authHeaderName,
        ).connector.copy(sourceUrl = "plugin:${File(declaration.documentPath).name}")
    }

    private fun pluginApiAuthType(raw: String): String =
        when (raw.trim().lowercase(Locale.ROOT).ifBlank { ApiConnectorAuthType.NONE }) {
            ApiConnectorAuthType.NONE -> ApiConnectorAuthType.NONE
            ApiConnectorAuthType.BEARER -> ApiConnectorAuthType.BEARER
            ApiConnectorAuthType.HEADER -> ApiConnectorAuthType.HEADER
            ApiConnectorAuthType.OAUTH2 -> throw CapabilityPluginException(
                "能力包不能声明 OAuth 凭据；请单独导入需要 OAuth 的 API 连接",
            )
            else -> throw CapabilityPluginException("API 声明包含不支持的认证方式")
        }

    private fun requiredString(root: JSONObject, key: String, max: Int): String {
        val value = root.optString(key).trim()
        if (value.isBlank() || value.length > max || value.any(Char::isISOControl)) {
            throw CapabilityPluginException("$key 应为 1-$max 个可见字符")
        }
        return value
    }

    private fun safeRelativePath(raw: String): String {
        val value = raw.trim().removePrefix("./").removeSuffix("/")
        if (value.isBlank() || value.startsWith('/') || value.startsWith('\\') ||
            '\\' in value || '\u0000' in value || value.any(Char::isISOControl)
        ) throw CapabilityPluginException("插件声明包含不安全路径")
        val segments = value.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." || it.length > 255 }) {
            throw CapabilityPluginException("插件声明路径无效")
        }
        return segments.joinToString("/")
    }

    private fun resolveContained(root: File, relative: String): File {
        val rootPath = root.canonicalFile.toPath()
        val file = File(root, relative).canonicalFile
        if (!file.toPath().startsWith(rootPath) || file.toPath() == rootPath) {
            throw CapabilityPluginException("插件文档路径越界")
        }
        return file
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private fun JSONArray?.objectsOrStrings(): List<Any> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) opt(index)?.takeUnless { it == JSONObject.NULL }?.let(::add)
        }
    }

    private companion object {
        const val PLUGIN_SCHEMA = "eta-plugin/v1"
        const val MAX_MANIFEST_BYTES = 128 * 1024L
        const val MAX_API_DOCUMENT_BYTES = 2 * 1024 * 1024L
        const val MAX_SKILLS = 64
        const val MAX_APIS = 16
        const val MAX_MCP_SERVERS = 16
    }
}
