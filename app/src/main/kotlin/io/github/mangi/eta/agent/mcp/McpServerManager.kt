package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.repository.McpServerRepository
import org.json.JSONObject

internal object McpServerManager {
    fun discover(
        server: McpServerSetting,
        bearerToken: String?,
    ): McpServerSetting = McpHttpClient(server, bearerToken).use { client ->
        val discovery = client.discoverTools()
        val availableNames = discovery.tools.mapTo(mutableSetOf()) { it.name }
        val refreshedAt = System.currentTimeMillis()
        server.copy(
            tools = discovery.tools,
            enabledToolNames = server.enabledToolNames.intersect(availableNames),
            lastRefreshedAt = refreshedAt,
            lastProtocolVersion = discovery.protocolVersion,
            toolsExpireAt = discovery.cacheTtlMs?.let { ttl ->
                if (ttl > Long.MAX_VALUE - refreshedAt) Long.MAX_VALUE else refreshedAt + ttl
            },
        )
    }

    fun discoverCatalog(
        server: McpServerSetting,
        bearerToken: String?,
    ): McpHttpClient.Catalog = McpHttpClient(server, bearerToken).use { client ->
        client.discoverCatalog()
    }

    fun readResource(
        server: McpServerSetting,
        bearerToken: String?,
        uri: String,
    ): String = McpHttpClient(server, bearerToken).use { client ->
        client.readResource(uri)
    }

    suspend fun refresh(serverId: String): McpServerSetting {
        val server = requireNotNull(McpServerRepository.serverById(serverId)) {
            "MCP 服务器不存在"
        }
        val refreshed = discover(server, McpServerRepository.bearerToken(server))
        McpServerRepository.update(refreshed)
        return refreshed
    }

    /** Explicit, user initiated tool probe used by the MCP detail screen. */
    suspend fun callTool(serverId: String, toolName: String, arguments: JSONObject): JSONObject {
        val server = requireNotNull(McpServerRepository.serverById(serverId)) { "MCP 服务器不存在" }
        val tool = server.tools.firstOrNull { it.name == toolName } ?: error("MCP 工具不存在")
        require(tool.name in server.enabledToolNames) { "请先启用此工具" }
        return McpHttpClient(server, McpServerRepository.bearerToken(server)).use { client ->
            client.callTool(tool, arguments)
        }
    }
}
