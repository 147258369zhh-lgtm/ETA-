package io.github.mangi.eta.agent.runtime

import org.json.JSONArray
import org.json.JSONObject

/**
 * Runtime-enforced tool boundary. A null allowlist is unrestricted; an empty allowlist
 * denies every tool. Entries may use a trailing `*` prefix match, and the denylist always wins.
 */
internal data class AgentToolPolicy(
    val allowedTools: List<String>? = null,
    val disallowedTools: List<String> = emptyList(),
) {
    private val allowed = allowedTools?.map(String::trim)?.toSet()
    private val denied = disallowedTools.map(String::trim).toSet()

    fun isAllowed(toolName: String): Boolean {
        val name = toolName.trim()
        if (name.isEmpty() || name in denied) return false
        val allow = allowed ?: return true
        return "*" in allow || name in allow || allow.any { pattern ->
            pattern.endsWith("*") && pattern.length > 1 && name.startsWith(pattern.dropLast(1))
        }
    }

    fun filterSchemas(schemas: JSONArray): JSONArray = JSONArray().also { filtered ->
        for (index in 0 until schemas.length()) {
            val schema = schemas.optJSONObject(index) ?: continue
            val name = schema.optJSONObject("function")?.optString("name").orEmpty()
            if (isAllowed(name)) filtered.put(JSONObject(schema.toString()))
        }
    }

    companion object {
        val UNRESTRICTED = AgentToolPolicy()
    }
}
