package io.github.mangi.eta.agent.capability

import org.json.JSONObject

internal enum class ManagedPackageKind(val label: String) {
    PLUGIN("插件"),
    SANDBOX("沙盒包"),
}

internal data class ManagedPackageRequirement(
    val id: String,
    val minVersion: String? = null,
    val maxVersion: String? = null,
)

internal data class ManagedToolDefinition(
    val name: String,
    val description: String,
    val parameters: JSONObject,
    val permissions: Set<String> = emptySet(),
)

internal data class ManagedSubpackage(
    val id: String,
    val entry: String,
    val tools: List<ManagedToolDefinition> = emptyList(),
)

internal data class ManagedToolPackage(
    val id: String,
    val kind: ManagedPackageKind,
    val name: String,
    val version: String,
    val description: String = "",
    val author: String = "",
    val category: String = "",
    val apiVersion: String = "",
    val entry: String = "main.js",
    val tools: List<ManagedToolDefinition> = emptyList(),
    val subpackages: List<ManagedSubpackage> = emptyList(),
    val requirements: List<ManagedPackageRequirement> = emptyList(),
    val permissions: Set<String> = emptySet(),
    val enabledByDefault: Boolean = true,
    val builtIn: Boolean = false,
    val installedAt: Long = System.currentTimeMillis(),
    val source: String = "",
    val sha256: String = "",
    val storagePath: String = "",
    val enabled: Boolean = enabledByDefault,
    val removed: Boolean = false,
    val disabledTools: Set<String> = emptySet(),
    val disabledSubpackages: Set<String> = emptySet(),
    val loadError: String? = null,
) {
    val activeTools: List<ManagedToolDefinition>
        get() = tools.filterNot { it.name in disabledTools }

    val toolCount: Int
        get() = tools.size + subpackages.sumOf { it.tools.size }
}

internal data class ManagedToolPackageInspection(
    val candidate: ManagedToolPackage,
    val warnings: List<String> = emptyList(),
    val replacesPackageId: String? = null,
)

internal sealed interface ManagedToolPackageInstallOutcome {
    data class Success(
        val packageId: String,
        val packageName: String,
        val kind: ManagedPackageKind,
        val toolCount: Int,
        val replaced: Boolean,
    ) : ManagedToolPackageInstallOutcome

    data class Failure(val message: String) : ManagedToolPackageInstallOutcome
}

internal class ManagedToolPackageException(message: String) : IllegalArgumentException(message)
