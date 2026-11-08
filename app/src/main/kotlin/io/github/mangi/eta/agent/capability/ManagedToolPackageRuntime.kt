package io.github.mangi.eta.agent.capability

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.tool.AgentLocalTools
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

internal data class ManagedPackageToolBinding(
    val modelName: String,
    val packageId: String,
    val packageName: String,
    val tool: ManagedToolDefinition,
    val entry: String,
    val subpackageId: String? = null,
)

/** Immutable tool inventory for one Agent run. Package changes take effect on the next run. */
internal class ManagedToolPackageRuntimeSnapshot(
    val context: Context,
    val bindings: List<ManagedPackageToolBinding>,
) {
    private val byModelName = bindings.associateBy(ManagedPackageToolBinding::modelName)

    fun resolve(modelName: String): ManagedPackageToolBinding? = byModelName[modelName]

    fun appendModelTools(destination: JSONArray) {
        bindings.forEach { binding ->
            val safety = if (binding.tool.permissions.isEmpty()) {
                "该工具通过 Eta 本地能力层执行。"
            } else {
                "声明能力：${binding.tool.permissions.sorted().joinToString("、")}。"
            }
            destination.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", binding.modelName)
                            .put(
                                "description",
                                buildString {
                                    append(binding.tool.description.ifBlank { binding.tool.name })
                                    append("（").append(binding.packageName)
                                    binding.subpackageId?.let { append(" / ").append(it) }
                                    append("）").append(safety)
                                }.take(1_200),
                            )
                            .put("parameters", binding.tool.parameters),
                    ),
            )
        }
    }

    fun sourceFile(binding: ManagedPackageToolBinding): File? {
        val pkg = ManagedToolPackageService(context).list().firstOrNull { it.id == binding.packageId }
            ?: return null
        if (pkg.storagePath.startsWith(ASSET_PREFIX)) return null
        val root = File(pkg.storagePath).canonicalFile
        val file = File(root, binding.entry).canonicalFile
        if (!file.toPath().startsWith(root.toPath()) || !file.isFile) return null
        return file
    }

    fun sourceText(binding: ManagedPackageToolBinding): String? {
        val pkg = ManagedToolPackageService(context).list().firstOrNull { it.id == binding.packageId }
            ?: return null
        if (pkg.storagePath.startsWith(ASSET_PREFIX)) {
            val assetPath = pkg.storagePath.removePrefix(ASSET_PREFIX)
            return runCatching { context.assets.open(assetPath).bufferedReader().use { it.readText() } }.getOrNull()
        }
        return sourceFile(binding)?.takeIf { it.length() <= MAX_SCRIPT_BYTES }?.readText(Charsets.UTF_8)
    }

    companion object {
        private const val ASSET_PREFIX = "asset://"
        private const val MAX_RUN_TOOLS = 160
        private const val MAX_SCRIPT_BYTES = 8L * 1024L * 1024L

        fun load(context: Context): ManagedToolPackageRuntimeSnapshot {
            val bindings = mutableListOf<ManagedPackageToolBinding>()
            ManagedToolPackageService(context).active().forEach { pkg ->
                pkg.activeTools.forEach { tool ->
                    if (bindings.size < MAX_RUN_TOOLS) {
                        bindings += binding(pkg, tool, pkg.entry)
                    }
                }
                pkg.subpackages
                    .filterNot { it.id in pkg.disabledSubpackages }
                    .forEach { subpackage ->
                        subpackage.tools.filterNot { it.name in pkg.disabledTools }.forEach { tool ->
                            if (bindings.size < MAX_RUN_TOOLS) {
                                bindings += binding(pkg, tool, subpackage.entry, subpackage.id)
                            }
                        }
                    }
            }
            return ManagedToolPackageRuntimeSnapshot(context.applicationContext, bindings)
        }

        private fun binding(
            pkg: ManagedToolPackage,
            tool: ManagedToolDefinition,
            entry: String,
            subpackageId: String? = null,
        ): ManagedPackageToolBinding {
            val packagePart = pkg.id.filter(Char::isLetterOrDigit).takeLast(6).ifBlank { "pkg" }
            val toolPart = tool.name
                .replace(Regex("[^A-Za-z0-9_-]"), "_")
                .trim('_')
                .take(34)
                .ifBlank { "tool" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("${pkg.id}\u0000${subpackageId.orEmpty()}\u0000${tool.name}".toByteArray())
                .take(4)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            val modelName = "plugin_${packagePart}_${toolPart}_$digest"
                .lowercase(Locale.ROOT)
                .take(64)
            return ManagedPackageToolBinding(modelName, pkg.id, pkg.name, tool, entry, subpackageId)
        }
    }
}

/**
 * Executes declared package tools in a short-lived, non-navigating WebView JavaScript sandbox.
 * Every host operation goes through AgentLocalTools, so package scripts cannot bypass Eta's
 * existing capability switches, accessibility preflight, approval checks or cancellation.
 */
internal class ManagedToolPackageToolExecutor(
    private val snapshot: ManagedToolPackageRuntimeSnapshot,
    private val localTools: AgentLocalTools,
) : CapabilityToolProvider {
    private val closed = AtomicBoolean(false)

    override fun contains(toolName: String): Boolean = snapshot.resolve(toolName) != null

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        if (closed.get()) return failure("PACKAGE_RUNTIME_CLOSED", "插件运行已结束")
        val binding = snapshot.resolve(toolCall.name)
            ?: return failure("UNKNOWN_PACKAGE_TOOL", "插件工具未在本次运行中启用")
        val script = snapshot.sourceText(binding)
            ?: return failure("PACKAGE_SOURCE_UNAVAILABLE", "无法读取插件入口脚本")
        val arguments = runCatching {
            JSONObject(toolCall.argumentsJson.ifBlank { "{}" })
        }.getOrElse {
            return failure("INVALID_ARGUMENT", "插件工具参数不是 JSON object")
        }
        return ManagedPackageWebSandbox(snapshot, localTools).execute(binding, script, arguments)
    }

    override fun close() {
        closed.set(true)
    }

    private companion object {
        fun failure(code: String, message: String): AgentModelClient.ToolResult = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("error", JSONObject().put("code", code).put("message", message))
                .toString(),
            sensitive = true,
        )
    }
}
