package io.github.mangi.eta.ui.screens.capability

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.capability.ManagedPackageKind
import io.github.mangi.eta.agent.capability.ManagedToolPackage
import io.github.mangi.eta.agent.capability.ManagedToolPackageInspection
import io.github.mangi.eta.agent.capability.ManagedToolPackageInstallOutcome
import io.github.mangi.eta.agent.capability.ManagedToolPackageService
import io.github.mangi.eta.ui.components.DetailListRow
import io.github.mangi.eta.ui.components.MiuixDialogActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun ManagedToolPackagesSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember(context.applicationContext) { ManagedToolPackageService(context.applicationContext) }
    var generation by remember { mutableIntStateOf(0) }
    var packages by remember { mutableStateOf<List<ManagedToolPackage>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<ManagedToolPackage?>(null) }
    var preview by remember { mutableStateOf<ManagedToolPackageInspection?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingName by remember { mutableStateOf("") }
    var remove by remember { mutableStateOf<ManagedToolPackage?>(null) }
    var message by remember { mutableStateOf<PackageSectionMessage?>(null) }

    LaunchedEffect(generation) {
        loading = true
        packages = withContext(Dispatchers.IO) { runCatching(service::list).getOrElse { emptyList() } }
        loading = false
    }

    fun install(replace: Boolean) {
        val uri = pendingUri ?: return
        val name = pendingName.ifBlank { "package.toolpkg" }
        scope.launch {
            working = true
            val outcome = withContext(Dispatchers.IO) {
                service.install({ context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件") }, name, replace)
            }
            when (outcome) {
                is ManagedToolPackageInstallOutcome.Success -> {
                    preview = null
                    pendingUri = null
                    message = PackageSectionMessage(
                        "已安装",
                        "${outcome.packageName} · ${outcome.toolCount} 个工具" + if (outcome.replaced) " · 已替换旧版本" else "",
                    )
                    generation += 1
                }
                is ManagedToolPackageInstallOutcome.Failure -> message = PackageSectionMessage(
                    "安装失败",
                    outcome.message,
                    true,
                )
            }
            working = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            working = true
            val name = withContext(Dispatchers.IO) { displayName(context, uri) }
            val inspected = withContext(Dispatchers.IO) {
                runCatching {
                    service.inspect({ context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件") }, name)
                }
            }
            inspected.onSuccess {
                pendingUri = uri
                pendingName = name
                preview = it
            }.onFailure {
                message = PackageSectionMessage("无法识别包", safePackageMessage(it), true)
            }
            working = false
        }
    }

    SmallTitle("插件与沙盒")
    Card(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
        DetailListRow(
            title = "导入插件或沙盒包",
            subtitle = "支持 ToolPkg、manifest 容器和 Operit JavaScript 包",
            icon = Icons.Rounded.UploadFile,
            value = when {
                working -> "正在读取"
                else -> "选择文件"
            },
            enabled = !working,
            onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "text/javascript", "*/*")) },
        )
        if (loading) {
            DetailListRow("正在载入", "校验包记录与工具声明", Icons.Rounded.Inventory2, enabled = false, onClick = {})
        } else if (packages.none { !it.removed }) {
            DetailListRow(
                title = "还没有启用中的包",
                subtitle = "导入后，声明的工具会出现在下一次 Agent 运行中",
                icon = Icons.Rounded.Inventory2,
                onClick = null,
            )
        } else {
            packages.filterNot { it.removed }.forEach { pkg ->
                DetailListRow(
                    title = pkg.name,
                    preview = pkg.description.takeIf(String::isNotBlank),
                    subtitle = packageSubtitle(pkg),
                    icon = if (pkg.kind == ManagedPackageKind.PLUGIN) Icons.Rounded.Extension else Icons.Rounded.Security,
                    value = when {
                        pkg.loadError != null -> "加载异常"
                        pkg.enabled -> "已启用"
                        else -> "已停用"
                    },
                    onClick = { detail = pkg },
                )
            }
        }
        if (packages.any { it.removed }) {
            DetailListRow(
                title = "恢复内置包",
                subtitle = "重新启用已隐藏的 Operit 默认沙盒包",
                icon = Icons.Rounded.Restore,
                onClick = {
                    scope.launch {
                        working = true
                        withContext(Dispatchers.IO) { service.restoreBuiltIns() }
                        generation += 1
                        working = false
                    }
                },
            )
        }
    }

    preview?.let { inspection ->
        val candidate = inspection.candidate
        WindowDialog(
            show = true,
            title = "安装 ${candidate.name}",
            summary = "${candidate.kind.label} · v${candidate.version} · ${candidate.toolCount} 个工具",
            onDismissRequest = { if (!working) preview = null },
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    PackageFact("来源", candidate.source.ifBlank { "本地文件" })
                    if (candidate.author.isNotBlank()) PackageFact("作者", candidate.author)
                    PackageFact("工具", candidate.toolCount.toString())
                    PackageFact("子包", candidate.subpackages.size.toString())
                    if (candidate.requirements.isNotEmpty()) {
                        PackageFact("依赖", candidate.requirements.joinToString(" · ") { it.id })
                    }
                    if (candidate.permissions.isNotEmpty()) {
                        PackageFact("能力", candidate.permissions.sorted().joinToString(" · "))
                    }
                    inspection.warnings.forEach { warning ->
                        Text(
                            warning,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                MiuixDialogActions(
                    modifier = Modifier.padding(top = 14.dp),
                    confirmText = when {
                        working -> "安装中"
                        inspection.replacesPackageId != null -> "替换并安装"
                        else -> "安装"
                    },
                    confirmEnabled = !working && candidate.toolCount > 0,
                    cancelEnabled = !working,
                    onCancel = { preview = null },
                    onConfirm = { install(inspection.replacesPackageId != null) },
                )
            }
        }
    }

    detail?.let { current ->
        WindowDialog(
            show = true,
            title = current.name,
            summary = "${current.kind.label} · v${current.version} · ${if (current.enabled) "已启用" else "已停用"}",
            onDismissRequest = { if (!working) detail = null },
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max = 620.dp)) {
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (current.loadError != null) {
                        PackageFact("加载错误", current.loadError)
                    }
                    SwitchPreference(
                        title = "启用此包",
                        summary = if (current.enabled) "工具将在下一次 Agent 运行中加载" else "包内容和偏好都会保留",
                        checked = current.enabled,
                        enabled = !working && !current.removed,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                working = true
                                runCatching { withContext(Dispatchers.IO) { service.setEnabled(current.id, enabled) } }
                                    .onFailure { message = PackageSectionMessage("状态未保存", safePackageMessage(it), true) }
                                val refreshed = withContext(Dispatchers.IO) { service.list() }
                                packages = refreshed
                                detail = refreshed.firstOrNull { it.id == current.id }
                                working = false
                            }
                        },
                    )
                    PackageFact("工具", buildString {
                        append(current.toolCount).append(" 个")
                        if (current.disabledTools.isNotEmpty()) append(" · ").append(current.disabledTools.size).append(" 个停用")
                    })
                    if (current.permissions.isNotEmpty()) {
                        PackageFact("申请能力", current.permissions.sorted().joinToString(" · "))
                    }
                    PackageFact("来源", current.source.ifBlank { "本地导入" })
                    if (current.sha256.isNotBlank()) PackageFact("SHA-256", current.sha256)
                    current.tools.take(40).forEach { tool ->
                        ToolToggleRow(
                            name = tool.name,
                            description = tool.description,
                            enabled = tool.name !in current.disabledTools,
                            interactive = !working,
                            onToggle = { enabled ->
                                scope.launch {
                                    working = true
                                    withContext(Dispatchers.IO) { service.setToolEnabled(current.id, tool.name, enabled) }
                                    val refreshed = withContext(Dispatchers.IO) { service.list() }
                                    packages = refreshed
                                    detail = refreshed.firstOrNull { it.id == current.id }
                                    working = false
                                }
                            },
                        )
                    }
                    current.subpackages.forEach { subpackage ->
                        val subpackageEnabled = subpackage.id !in current.disabledSubpackages
                        ToolToggleRow(
                            name = subpackage.id,
                            description = "子包 · ${subpackage.tools.size} 个工具 · ${subpackage.entry}",
                            enabled = subpackageEnabled,
                            interactive = !working,
                            onToggle = { enabled ->
                                scope.launch {
                                    working = true
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            service.setSubpackageEnabled(current.id, subpackage.id, enabled)
                                        }
                                    }.onFailure {
                                        message = PackageSectionMessage("子包状态未保存", safePackageMessage(it), true)
                                    }
                                    val refreshed = withContext(Dispatchers.IO) { service.list() }
                                    packages = refreshed
                                    detail = refreshed.firstOrNull { it.id == current.id }
                                    working = false
                                }
                            },
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(
                        text = "关闭",
                        onClick = { detail = null },
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        text = if (current.builtIn) "移除" else "删除",
                        onClick = { remove = current },
                        enabled = !working,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    remove?.let { current ->
        WindowDialog(
            show = true,
            title = if (current.builtIn) "移除 ${current.name}？" else "删除 ${current.name}？",
            summary = if (current.builtIn) {
                "内置包可以从“恢复内置包”恢复；依赖它的启用包会阻止本次移除。"
            } else {
                "包目录、工具声明和启用偏好会一并删除。依赖它的启用包会阻止本次操作。"
            },
            onDismissRequest = { if (!working) remove = null },
        ) {
            MiuixDialogActions(
                confirmText = if (working) "处理中" else if (current.builtIn) "移除" else "删除",
                destructive = true,
                confirmEnabled = !working,
                cancelEnabled = !working,
                onCancel = { remove = null },
                onConfirm = {
                    scope.launch {
                        working = true
                        runCatching { withContext(Dispatchers.IO) { service.delete(current.id) } }
                            .onSuccess {
                                remove = null
                                detail = null
                                generation += 1
                            }
                            .onFailure { message = PackageSectionMessage("无法删除", safePackageMessage(it), true) }
                        working = false
                    }
                },
            )
        }
    }

    message?.let { current ->
        WindowDialog(
            show = true,
            title = current.title,
            summary = current.message,
            onDismissRequest = { message = null },
        ) {
            TextButton(text = "知道了", onClick = { message = null }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ToolToggleRow(
    name: String,
    description: String,
    enabled: Boolean,
    interactive: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 5.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                description.ifBlank { "Operit 工具" },
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(
            text = if (enabled) "停用" else "启用",
            enabled = interactive,
            onClick = { onToggle(!enabled) },
            modifier = Modifier.width(72.dp),
        )
    }
}

@Composable
private fun PackageFact(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, style = MiuixTheme.textStyles.footnote1, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

private data class PackageSectionMessage(val title: String, val message: String, val error: Boolean = false)

private fun packageSubtitle(pkg: ManagedToolPackage): String = buildString {
    append('v').append(pkg.version.take(24))
    append(" · ").append(pkg.toolCount).append(" 个工具")
    if (pkg.subpackages.isNotEmpty()) append(" · ").append(pkg.subpackages.size).append(" 个子包")
    if (!pkg.enabled) append(" · 已停用")
}

private fun displayName(context: Context, uri: Uri): String {
    val fromProvider = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    return fromProvider?.takeIf(String::isNotBlank)?.take(180) ?: uri.lastPathSegment?.take(180) ?: "package"
}

private fun safePackageMessage(error: Throwable): String {
    if (error is CancellationException) throw error
    return (error.message ?: error.javaClass.simpleName).take(300)
}
