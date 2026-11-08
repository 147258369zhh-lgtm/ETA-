package io.github.mangi.eta.agent.capability

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/** A small crash journal written before the first plugin mutation. */
internal class CapabilityPluginInstallJournal(context: Context) {
    private val file = File(
        context.applicationContext.filesDir,
        "capability-center/plugin-install-journal.json",
    )

    @Synchronized
    fun load(): CapabilityPluginInstallTransaction? {
        if (!file.isFile) return null
        return runCatching {
            val value = JSONObject(file.readText(Charsets.UTF_8))
            require(value.optInt("version") == VERSION)
            CapabilityPluginInstallTransaction(
                pluginName = value.getString("pluginName").take(80),
                version = value.getString("versionName").take(64),
                pluginRecordId = value.getString("pluginRecordId"),
                skillIds = value.optJSONArray("skillIds").strings(),
                apiConnectorIds = value.optJSONArray("apiConnectorIds").strings(),
                mcpServerIds = value.optJSONArray("mcpServerIds").strings(),
                startedAt = value.optLong("startedAt", 0L),
            ).takeIf {
                it.pluginName.isNotBlank() && it.pluginRecordId.startsWith("plugin-")
            }
        }.getOrNull()
    }

    @Synchronized
    fun write(journal: CapabilityPluginInstallTransaction) {
        val value = JSONObject()
            .put("version", VERSION)
            .put("pluginName", journal.pluginName)
            .put("versionName", journal.version)
            .put("pluginRecordId", journal.pluginRecordId)
            .put("skillIds", JSONArray(journal.skillIds))
            .put("apiConnectorIds", JSONArray(journal.apiConnectorIds))
            .put("mcpServerIds", JSONArray(journal.mcpServerIds))
            .put("startedAt", journal.startedAt)
        val parent = requireNotNull(file.parentFile) { "能力包恢复目录无效" }
        check(parent.mkdirs() || parent.isDirectory) { "无法创建能力包恢复目录" }
        val temporary = File.createTempFile("eta-plugin-journal-", ".tmp", parent)
        try {
            temporary.writeText(value.toString(), Charsets.UTF_8)
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

    @Synchronized
    fun clear() {
        Files.deleteIfExists(file.toPath())
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private companion object {
        const val VERSION = 1
    }
}
