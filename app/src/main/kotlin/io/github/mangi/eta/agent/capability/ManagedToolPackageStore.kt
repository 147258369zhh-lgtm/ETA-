package io.github.mangi.eta.agent.capability

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

internal data class ManagedToolPackagePreference(
    val enabled: Boolean? = null,
    val removed: Boolean? = null,
    val disabledTools: Set<String> = emptySet(),
    val disabledSubpackages: Set<String> = emptySet(),
)

/** Stores package records and lightweight built-in overrides in one atomic document. */
internal class ManagedToolPackageStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "capability-center/managed-toolpackages.json")

    @Synchronized
    fun installed(): List<ManagedToolPackage> = read().packages.values.toList()

    @Synchronized
    fun preference(id: String): ManagedToolPackagePreference =
        read().preferences[id] ?: ManagedToolPackagePreference()

    @Synchronized
    fun upsert(pkg: ManagedToolPackage) {
        val state = read()
        val packages = state.packages.toMutableMap()
        packages[pkg.id] = pkg
        write(state.copy(packages = packages))
    }

    @Synchronized
    fun delete(id: String) {
        val state = read()
        val packages = state.packages.toMutableMap()
        packages.remove(id)
        val preferences = state.preferences.toMutableMap().also { it.remove(id) }
        write(state.copy(packages = packages, preferences = preferences))
    }

    @Synchronized
    fun savePreference(id: String, preference: ManagedToolPackagePreference) {
        val state = read()
        val preferences = state.preferences.toMutableMap()
        preferences[id] = preference
        write(state.copy(preferences = preferences))
    }

    private data class State(
        val packages: Map<String, ManagedToolPackage>,
        val preferences: Map<String, ManagedToolPackagePreference>,
    )

    private fun read(): State {
        if (!file.isFile) return State(emptyMap(), emptyMap())
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            require(root.optInt("version") == VERSION)
            val packages = linkedMapOf<String, ManagedToolPackage>()
            root.optJSONArray("packages")?.let { values ->
                for (index in 0 until values.length()) {
                    values.optJSONObject(index)?.let(::parsePackage)?.let { packages[it.id] = it }
                }
            }
            val preferences = linkedMapOf<String, ManagedToolPackagePreference>()
            root.optJSONObject("preferences")?.let { values ->
                val keys = values.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    values.optJSONObject(key)?.let { preferences[key] = parsePreference(it) }
                }
            }
            State(packages, preferences)
        }.getOrDefault(State(emptyMap(), emptyMap()))
    }

    private fun write(state: State) {
        val root = JSONObject()
            .put("version", VERSION)
            .put("packages", JSONArray().also { array ->
                state.packages.values.sortedBy { it.id }.forEach { array.put(toJson(it)) }
            })
            .put("preferences", JSONObject().also { objectValue ->
                state.preferences.toSortedMap().forEach { (id, preference) ->
                    objectValue.put(id, toJson(preference))
                }
            })
        val parent = requireNotNull(file.parentFile) { "插件记录目录无效" }
        check(parent.mkdirs() || parent.isDirectory) { "无法创建插件记录目录" }
        val temporary = File.createTempFile("eta-toolpackages-", ".tmp", parent)
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

    private fun parsePackage(value: JSONObject): ManagedToolPackage? = runCatching {
        ManagedToolPackage(
            id = value.getString("id"),
            kind = ManagedPackageKind.valueOf(value.getString("kind")),
            name = value.getString("name"),
            version = value.optString("version", "1.0.0"),
            description = value.optString("description"),
            author = value.optString("author"),
            category = value.optString("category"),
            apiVersion = value.optString("apiVersion"),
            entry = value.optString("entry", "main.js"),
            tools = parseTools(value.optJSONArray("tools")),
            subpackages = value.optJSONArray("subpackages")?.let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.let { item ->
                            add(ManagedSubpackage(
                                id = item.optString("id"),
                                entry = item.optString("entry"),
                                tools = parseTools(item.optJSONArray("tools")),
                            ))
                        }
                    }
                }
            }.orEmpty(),
            requirements = value.optJSONArray("requirements")?.let { array ->
                buildList {
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.let { item ->
                            item.optString("id").takeIf(String::isNotBlank)?.let { id ->
                                add(ManagedPackageRequirement(
                                    id = id,
                                    minVersion = item.optString("minVersion").takeIf(String::isNotBlank),
                                    maxVersion = item.optString("maxVersion").takeIf(String::isNotBlank),
                                ))
                            }
                        }
                    }
                }
            }.orEmpty(),
            permissions = value.optJSONArray("permissions").strings().toSet(),
            enabledByDefault = value.optBoolean("enabledByDefault", true),
            builtIn = value.optBoolean("builtIn", false),
            installedAt = value.optLong("installedAt", 0L),
            source = value.optString("source"),
            sha256 = value.optString("sha256"),
            storagePath = value.optString("storagePath"),
        ).takeIf { it.id.isNotBlank() && it.name.isNotBlank() }
    }.getOrNull()

    private fun parseTools(raw: JSONArray?): List<ManagedToolDefinition> = buildList {
        if (raw == null) return@buildList
        for (index in 0 until raw.length()) {
            val item = raw.optJSONObject(index) ?: continue
            val name = item.optString("name").takeIf(String::isNotBlank) ?: continue
            add(ManagedToolDefinition(
                name = name,
                description = item.optString("description"),
                parameters = item.optJSONObject("parameters") ?: JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()),
                permissions = item.optJSONArray("permissions").strings().toSet(),
            ))
        }
    }

    private fun toJson(pkg: ManagedToolPackage): JSONObject = JSONObject()
        .put("id", pkg.id)
        .put("kind", pkg.kind.name)
        .put("name", pkg.name)
        .put("version", pkg.version)
        .put("description", pkg.description)
        .put("author", pkg.author)
        .put("category", pkg.category)
        .put("apiVersion", pkg.apiVersion)
        .put("entry", pkg.entry)
        .put("tools", JSONArray().also { array -> pkg.tools.forEach { array.put(toJson(it)) } })
        .put("subpackages", JSONArray().also { array ->
            pkg.subpackages.forEach { subpackage ->
                array.put(JSONObject()
                    .put("id", subpackage.id)
                    .put("entry", subpackage.entry)
                    .put("tools", JSONArray().also { tools -> subpackage.tools.forEach { tools.put(toJson(it)) } }))
            }
        })
        .put("requirements", JSONArray().also { array ->
            pkg.requirements.forEach { requirement ->
                array.put(JSONObject()
                    .put("id", requirement.id)
                    .put("minVersion", requirement.minVersion ?: JSONObject.NULL)
                    .put("maxVersion", requirement.maxVersion ?: JSONObject.NULL))
            }
        })
        .put("permissions", JSONArray(pkg.permissions.sorted()))
        .put("enabledByDefault", pkg.enabledByDefault)
        .put("builtIn", pkg.builtIn)
        .put("installedAt", pkg.installedAt)
        .put("source", pkg.source)
        .put("sha256", pkg.sha256)
        .put("storagePath", pkg.storagePath)

    private fun toJson(tool: ManagedToolDefinition): JSONObject = JSONObject()
        .put("name", tool.name)
        .put("description", tool.description)
        .put("parameters", tool.parameters)
        .put("permissions", JSONArray(tool.permissions.sorted()))

    private fun parsePreference(value: JSONObject): ManagedToolPackagePreference = ManagedToolPackagePreference(
        enabled = value.opt("enabled")?.takeUnless { it == JSONObject.NULL }?.let { value.optBoolean("enabled") },
        removed = value.opt("removed")?.takeUnless { it == JSONObject.NULL }?.let { value.optBoolean("removed") },
        disabledTools = value.optJSONArray("disabledTools").strings().toSet(),
        disabledSubpackages = value.optJSONArray("disabledSubpackages").strings().toSet(),
    )

    private fun toJson(preference: ManagedToolPackagePreference): JSONObject = JSONObject()
        .put("enabled", preference.enabled ?: JSONObject.NULL)
        .put("removed", preference.removed ?: JSONObject.NULL)
        .put("disabledTools", JSONArray(preference.disabledTools.sorted()))
        .put("disabledSubpackages", JSONArray(preference.disabledSubpackages.sorted()))

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private companion object {
        const val VERSION = 1
    }
}
