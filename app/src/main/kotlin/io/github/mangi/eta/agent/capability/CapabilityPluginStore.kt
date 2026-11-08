package io.github.mangi.eta.agent.capability

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal class CapabilityPluginStore(context: Context) {
    private val file = File(
        context.applicationContext.filesDir,
        "capability-center/installed-plugins.json",
    )

    @Synchronized
    fun load(): List<InstalledCapabilityPlugin> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            require(root.optInt("version") == VERSION)
            val values = root.optJSONArray("plugins") ?: JSONArray()
            buildList {
                for (index in 0 until values.length()) {
                    values.optJSONObject(index)?.let(::parse)?.let(::add)
                }
            }.distinctBy(InstalledCapabilityPlugin::id)
                .sortedByDescending(InstalledCapabilityPlugin::installedAt)
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun save(plugins: List<InstalledCapabilityPlugin>): List<InstalledCapabilityPlugin> {
        require(plugins.size <= MAX_PLUGINS) { "最多记录 $MAX_PLUGINS 个能力包" }
        write(plugins)
        return plugins.sortedByDescending(InstalledCapabilityPlugin::installedAt)
    }

    @Synchronized
    fun upsert(plugin: InstalledCapabilityPlugin): List<InstalledCapabilityPlugin> {
        val current = load()
        val index = current.indexOfFirst { it.id == plugin.id }
        return save(
            if (index >= 0) current.toMutableList().also { it[index] = plugin } else current + plugin,
        )
    }

    @Synchronized
    fun delete(pluginId: String): List<InstalledCapabilityPlugin> =
        save(load().filterNot { it.id == pluginId })

    private fun parse(value: JSONObject): InstalledCapabilityPlugin? = runCatching {
        InstalledCapabilityPlugin(
            id = value.getString("id"),
            name = value.getString("name"),
            version = value.getString("version"),
            description = value.optString("description"),
            skillIds = value.optJSONArray("skillIds").strings(),
            apiConnectorIds = value.optJSONArray("apiConnectorIds").strings(),
            mcpServerIds = value.optJSONArray("mcpServerIds").strings(),
            installedAt = value.optLong("installedAt", 0L),
        ).takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
    }.getOrNull()

    private fun write(plugins: List<InstalledCapabilityPlugin>) {
        val root = JSONObject()
            .put("version", VERSION)
            .put("plugins", JSONArray().also { values ->
                plugins.sortedWith(compareBy { it.name.lowercase(Locale.ROOT) }).forEach { plugin ->
                    values.put(
                        JSONObject()
                            .put("id", plugin.id)
                            .put("name", plugin.name)
                            .put("version", plugin.version)
                            .put("description", plugin.description)
                            .put("skillIds", JSONArray(plugin.skillIds))
                            .put("apiConnectorIds", JSONArray(plugin.apiConnectorIds))
                            .put("mcpServerIds", JSONArray(plugin.mcpServerIds))
                            .put("installedAt", plugin.installedAt),
                    )
                }
            })
        val parent = requireNotNull(file.parentFile) { "能力包记录目录无效" }
        check(parent.mkdirs() || parent.isDirectory) { "无法创建能力包记录目录" }
        val temporary = File.createTempFile("eta-capability-plugins-", ".tmp", parent)
        try {
            temporary.writeText(root.toString(), Charsets.UTF_8)
            runCatching {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private companion object {
        const val VERSION = 1
        const val MAX_PLUGINS = 64
    }
}
