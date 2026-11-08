package io.github.mangi.eta.agent.capability

import java.io.File
import java.security.MessageDigest
import java.util.Locale
import org.hjson.JsonValue
import org.json.JSONArray
import org.json.JSONObject

internal object ManagedToolPackageParser {
    private const val MAX_SCRIPT_BYTES = 8L * 1024L * 1024L
    private const val MAX_TOOLS = 160
    private const val MAX_SUBPACKAGES = 64
    private val metadataPattern = Regex("/\\*\\s*METADATA\\s*([\\s\\S]*?)\\s*\\*/", RegexOption.IGNORE_CASE)
    private val hostCallPattern = Regex("Tools\\.([A-Za-z0-9_.]+)")

    fun parseFlatSandbox(
        scriptFile: File,
        fallbackName: String,
        source: String,
        sha256: String,
        builtIn: Boolean,
        storagePath: String,
        installedAt: Long = System.currentTimeMillis(),
    ): ManagedToolPackage {
        if (!scriptFile.isFile) throw ManagedToolPackageException("沙盒包脚本不存在")
        if (scriptFile.length() > MAX_SCRIPT_BYTES) throw ManagedToolPackageException("沙盒包脚本超过 8 MiB")
        val script = scriptFile.readText(Charsets.UTF_8)
        val metadata = extractMetadata(script)
            ?: JSONObject().put("name", fallbackName.removeSuffix(".js"))
        val rawName = metadata.optString("name").trim().ifBlank { fallbackName.removeSuffix(".js") }
        val id = "operit.sandbox." + normalizeId(rawName)
        val tools = parseTools(metadata.optJSONArray("tools"), script)
        return ManagedToolPackage(
            id = id,
            kind = ManagedPackageKind.SANDBOX,
            name = localized(metadata.opt("display_name"), localStorage = true).ifBlank { rawName },
            version = metadata.optString("version").trim().ifBlank { "1.0.0" },
            description = localized(metadata.opt("description"), localStorage = true),
            author = localized(metadata.opt("author"), localStorage = true),
            category = metadata.optString("category").trim(),
            entry = "main.js",
            tools = tools,
            permissions = permissionsFor(script, metadata),
            enabledByDefault = metadata.optBoolean(
                "enabled_by_default",
                metadata.optBoolean("enabledByDefault", true),
            ),
            builtIn = builtIn,
            installedAt = installedAt,
            source = source,
            sha256 = sha256,
            storagePath = storagePath,
        )
    }

    fun parsePlugin(root: File, source: String, sha256: String, storagePath: String): ManagedToolPackage {
        val manifestFile = listOf("manifest.json", "manifest.hjson")
            .map { File(root, it) }
            .firstOrNull(File::isFile)
            ?: throw ManagedToolPackageException("插件缺少 manifest.json 或 manifest.hjson")
        if (manifestFile.length() > 256L * 1024L) {
            throw ManagedToolPackageException("插件清单超过 256 KiB")
        }
        val manifest = parseDocument(manifestFile.readText(Charsets.UTF_8))
        val id = manifest.optString("toolpkg_id").trim().ifBlank { manifest.optString("id").trim() }
        if (!id.matches(ID_PATTERN)) throw ManagedToolPackageException("插件 ID 无效，需使用反向域名或稳定英文标识")
        val mainEntry = safeRelativePath(manifest.optString("main").trim().ifBlank { "main.js" })
        val mainFile = resolve(root, mainEntry)
        if (!mainFile.isFile) throw ManagedToolPackageException("插件入口不存在：$mainEntry")
        if (mainFile.length() > MAX_SCRIPT_BYTES) throw ManagedToolPackageException("插件入口超过 8 MiB")
        val mainScript = mainFile.readText(Charsets.UTF_8)
        val subpackages = mutableListOf<ManagedSubpackage>()
        manifest.optJSONArray("subpackages")?.objects().orEmpty().forEach { item ->
            if (subpackages.size >= MAX_SUBPACKAGES) throw ManagedToolPackageException("插件子包超过 $MAX_SUBPACKAGES 个")
            val subpackageId = item.optString("id").trim()
            val entry = safeRelativePath(item.optString("entry").trim())
            if (subpackageId.isBlank() || entry.isBlank()) {
                throw ManagedToolPackageException("插件子包必须同时声明 id 与 entry")
            }
            val file = resolve(root, entry)
            if (!file.isFile) throw ManagedToolPackageException("插件子包入口不存在：$entry")
            if (file.length() > MAX_SCRIPT_BYTES) throw ManagedToolPackageException("插件子包脚本超过 8 MiB")
            val script = file.readText(Charsets.UTF_8)
            subpackages += ManagedSubpackage(
                id = subpackageId.take(64),
                entry = entry,
                tools = parseTools(extractMetadata(script)?.optJSONArray("tools"), script),
            )
        }
        val directTools = parseTools(extractMetadata(mainScript)?.optJSONArray("tools"), mainScript)
        val allScripts = buildString {
            append(mainScript)
            subpackages.forEach { append('\n'); runCatching { append(resolve(root, it.entry).readText()) } }
        }
        val requirements = manifest.optJSONArray("requires").objectsOrStrings().mapNotNull { value ->
            when (value) {
                is String -> value.trim().takeIf(String::isNotBlank)?.let { ManagedPackageRequirement(it) }
                is JSONObject -> value.optString("id").trim().takeIf(String::isNotBlank)?.let { requiredId ->
                    ManagedPackageRequirement(
                        id = requiredId,
                        minVersion = value.optString("min_version").trim().takeIf(String::isNotBlank),
                        maxVersion = value.optString("max_version").trim().takeIf(String::isNotBlank),
                    )
                }
                else -> null
            }
        }.take(32)
        return ManagedToolPackage(
            id = id,
            kind = ManagedPackageKind.PLUGIN,
            name = localized(manifest.opt("display_name"), localStorage = true).ifBlank { id },
            version = manifest.optString("version").trim().ifBlank { "1.0.0" },
            description = localized(manifest.opt("description"), localStorage = true),
            author = localized(manifest.opt("author"), localStorage = true),
            apiVersion = manifest.optString("api_version").trim(),
            entry = mainEntry,
            tools = directTools,
            subpackages = subpackages,
            requirements = requirements,
            permissions = permissionsFor(allScripts, manifest),
            enabledByDefault = manifest.optBoolean("enabled_by_default", true),
            source = source,
            sha256 = sha256,
            storagePath = storagePath,
        )
    }

    fun metadataForFlat(scriptFile: File, fallbackName: String, source: String): ManagedToolPackage =
        parseFlatSandbox(
            scriptFile = scriptFile,
            fallbackName = fallbackName,
            source = source,
            sha256 = sha256(scriptFile),
            builtIn = true,
            storagePath = scriptFile.absolutePath,
        )

    fun sha256(file: File): String = MessageDigest.getInstance("SHA-256").also { digest ->
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
    }.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun parseDocument(raw: String): JSONObject {
        val text = raw.removePrefix("\uFEFF").trim()
        if (text.isEmpty()) throw ManagedToolPackageException("插件清单为空")
        return runCatching { JSONObject(text) }.getOrElse {
            runCatching {
                JSONObject(JsonValue.readHjson(text).asObject().toString())
            }.getOrElse { throw ManagedToolPackageException("插件清单不是有效 JSON 或 HJSON") }
        }
    }

    private fun extractMetadata(script: String): JSONObject? {
        val raw = metadataPattern.find(script)?.groupValues?.getOrNull(1)?.trim() ?: return null
        return runCatching { parseDocument(raw) }.getOrNull()
    }

    private fun parseTools(raw: JSONArray?, script: String): List<ManagedToolDefinition> {
        val scriptPermissions = permissionsFor(script, null)
        val result = mutableListOf<ManagedToolDefinition>()
        if (raw != null) {
            for (index in 0 until raw.length()) {
                val item = raw.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                if (!name.matches(TOOL_NAME_PATTERN)) continue
                if (result.any { it.name == name }) continue
                result += ManagedToolDefinition(
                    name = name,
                    description = localized(item.opt("description"), localStorage = true),
                    parameters = parameters(item.optJSONArray("parameters")),
                    permissions = packagePermissions(item.opt("permissions")) + scriptPermissions,
                )
                if (result.size >= MAX_TOOLS) break
            }
        }
        return result
    }

    private fun parameters(raw: JSONArray?): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        raw?.objects().orEmpty().take(MAX_TOOLS).forEach { item ->
            val name = item.optString("name").trim()
            if (!name.matches(TOOL_NAME_PATTERN)) return@forEach
            val type = item.optString("type", "string").trim().lowercase(Locale.ROOT)
            val schema = JSONObject()
                .put("type", if (type in SUPPORTED_TYPES) type else "string")
                .put("description", localized(item.opt("description"), localStorage = true))
            item.optJSONArray("enum")?.let { schema.put("enum", it) }
            item.optJSONObject("items")?.let { schema.put("items", it) }
            if (item.optBoolean("required", false)) required.put(name)
            properties.put(name, schema)
        }
        return JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", required)
            .put("additionalProperties", false)
    }

    private fun permissionsFor(script: String, manifest: JSONObject?): Set<String> = buildSet {
        manifest?.opt("permissions")?.let { addAll(packagePermissions(it)) }
        hostCallPattern.findAll(script).forEach { match ->
            val call = match.groupValues[1]
            when {
                call.startsWith("Files.") -> add("files")
                call.startsWith("System.") -> add("system")
                call.startsWith("UI.") -> add("ui")
                call.startsWith("Net.") || call.contains("http_request", true) -> add("network")
                call.startsWith("Memory.") || call.contains("memory", true) -> add("memory")
                call.startsWith("Chat.") -> add("conversations")
                call.startsWith("Workflow") -> add("automation")
                call.startsWith("SoftwareSettings.") -> add("software")
                call.startsWith("FFmpeg") || call.startsWith("ffmpeg") -> add("terminal")
                else -> Unit
            }
        }
    }

    private fun packagePermissions(value: Any?): Set<String> {
        val array = value as? JSONArray ?: return emptySet()
        return buildSet {
            for (index in 0 until array.length()) array.optString(index).trim().lowercase(Locale.ROOT)
                .takeIf(String::isNotBlank)?.take(40)?.let(::add)
        }
    }

    private fun localized(value: Any?, localStorage: Boolean): String {
        if (value == null || value == JSONObject.NULL) return ""
        if (value is String) return value.trim().take(4_000)
        val objectValue = value as? JSONObject ?: return value.toString().trim().take(4_000)
        val language = Locale.getDefault().language.lowercase(Locale.ROOT)
        return sequenceOf(language, if (language == "zh") "zh-CN" else "", "default", "en")
            .firstOrNull { key -> key.isNotBlank() && objectValue.optString(key).isNotBlank() }
            ?.let(objectValue::optString)
            ?.trim()
            ?.take(4_000)
            .orEmpty()
    }

    private fun safeRelativePath(raw: String): String {
        val value = raw.trim().replace('\\', '/')
        if (value.isBlank() || value.startsWith('/') || ':' in value || value.any(Char::isISOControl)) {
            throw ManagedToolPackageException("插件包包含不安全路径")
        }
        val parts = value.split('/')
        if (parts.size > 16 || parts.any { it.isBlank() || it == "." || it == ".." }) {
            throw ManagedToolPackageException("插件包路径无效：$raw")
        }
        return parts.joinToString("/")
    }

    private fun resolve(root: File, relative: String): File {
        val file = File(root, relative)
        val rootPath = root.canonicalFile.toPath()
        if (!file.canonicalFile.toPath().startsWith(rootPath)) throw ManagedToolPackageException("插件路径越界")
        return file
    }

    private fun normalizeId(raw: String): String = raw.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9._-]+"), ".")
        .trim('.', '-', '_')
        .take(80)
        .ifBlank { "unnamed" }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) optJSONObject(index)?.let(::add)
        }
    }

    private fun JSONArray?.objectsOrStrings(): List<Any> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.let(::add)
                if (opt(index) is String) optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private val ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{1,127}")
    private val TOOL_NAME_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    private val SUPPORTED_TYPES = setOf("string", "number", "integer", "boolean", "object", "array")
}
