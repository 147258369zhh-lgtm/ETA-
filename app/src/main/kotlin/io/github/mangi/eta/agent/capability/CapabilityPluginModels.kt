package io.github.mangi.eta.agent.capability

import io.github.mangi.eta.data.model.McpAuthorizationType

internal data class CapabilityPluginApiDeclaration(
    val name: String,
    val documentPath: String,
    val authType: String = ApiConnectorAuthType.NONE,
    val authHeaderName: String = "X-API-Key",
)

internal data class CapabilityPluginMcpDeclaration(
    val name: String,
    val url: String,
    val authorizationType: String = McpAuthorizationType.NONE,
)

internal data class CapabilityPluginManifest(
    val name: String,
    val version: String,
    val description: String = "",
    val author: String = "",
    val skills: List<String> = emptyList(),
    val apis: List<CapabilityPluginApiDeclaration> = emptyList(),
    val mcpServers: List<CapabilityPluginMcpDeclaration> = emptyList(),
    val permissions: List<String> = emptyList(),
)

internal data class CapabilityPluginPreview(
    val manifest: CapabilityPluginManifest,
    val skillPaths: List<String>,
    val plannedSkillIds: List<String> = emptyList(),
    val apiOperationCount: Int,
    val warnings: List<String> = emptyList(),
)

internal data class CapabilityPluginInstallTransaction(
    val pluginName: String,
    val version: String,
    val pluginRecordId: String,
    val skillIds: List<String> = emptyList(),
    val apiConnectorIds: List<String> = emptyList(),
    val mcpServerIds: List<String> = emptyList(),
    val startedAt: Long = System.currentTimeMillis(),
)

internal data class InstalledCapabilityPlugin(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val skillIds: List<String> = emptyList(),
    val apiConnectorIds: List<String> = emptyList(),
    val mcpServerIds: List<String> = emptyList(),
    val installedAt: Long = System.currentTimeMillis(),
)

sealed interface CapabilityPluginInstallOutcome {
    data class Success(
        val pluginName: String,
        val installedSkills: Int,
        val installedApis: Int,
        val installedMcpServers: Int,
    ) : CapabilityPluginInstallOutcome

    data class Failure(
        val message: String,
        val recoveryRequired: Boolean = false,
    ) : CapabilityPluginInstallOutcome
}

sealed interface CapabilityPluginUninstallOutcome {
    data object Success : CapabilityPluginUninstallOutcome

    data class Failure(
        val message: String,
        val recoveryRequired: Boolean = false,
    ) : CapabilityPluginUninstallOutcome
}

internal class CapabilityPluginException(message: String) : IllegalArgumentException(message)
