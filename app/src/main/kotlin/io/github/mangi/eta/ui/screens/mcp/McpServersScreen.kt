package io.github.mangi.eta.ui.screens.mcp

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.mcp.McpServerManager
import io.github.mangi.eta.agent.mcp.validateMcpEndpoint
import io.github.mangi.eta.agent.capability.McpPortableCodec
import io.github.mangi.eta.agent.capability.McpPortableConfigService
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpTransportType
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.data.repository.McpCredentialStatus
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.DetailListRow
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import org.json.JSONObject

@Composable
internal fun McpServersScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val servers by McpServerRepository.serversFlow().collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var transportType by remember { mutableStateOf(McpTransportType.HTTP) }
    var stdioCommand by remember { mutableStateOf("") }
    var stdioArguments by remember { mutableStateOf("") }
    var launchCommand by remember { mutableStateOf("") }
    var workingDirectory by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val portableService = remember(context.applicationContext) {
        McpPortableConfigService(context.applicationContext)
    }
    var batchWorking by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || batchWorking) return@rememberLauncherForActivityResult
        scope.launch {
            batchWorking = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    portableService.importJson(readBoundedMcpConfig(context, uri))
                }
            }
            batchWorking = false
            result.onSuccess { imported ->
                val credentialHint = if (imported.credentialRequired > 0) {
                    "；${imported.credentialRequired} 个连接需要配置令牌后才能使用"
                } else ""
                val duplicateHint = if (imported.skippedDuplicates > 0) {
                    "；跳过 ${imported.skippedDuplicates} 个重复地址"
                } else ""
                Toast.makeText(
                    context,
                    "已导入 ${imported.imported} 个 MCP 连接$credentialHint$duplicateHint",
                    Toast.LENGTH_LONG,
                ).show()
            }.onFailure {
                Toast.makeText(context, it.message ?: "MCP 连接导入失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null || batchWorking) return@rememberLauncherForActivityResult
        scope.launch {
            batchWorking = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = portableService.exportJson()
                    val output = context.contentResolver.openOutputStream(uri)
                        ?: error("无法写入所选文件")
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
                    servers.size
                }
            }
            batchWorking = false
            Toast.makeText(
                context,
                result.fold(
                    onSuccess = { "已导出 $it 个 MCP 连接，未包含任何令牌" },
                    onFailure = { it.message ?: "MCP 连接导出失败" },
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.route_mcp_servers),
        onBack = onBack,
        actions = {
            IconButton(enabled = !batchWorking, onClick = { showAdd = true }) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.mcp_add_server),
                )
            }
        },
    ) {
        item(key = "servers") {
            SmallTitle(stringResource(R.string.mcp_configured_servers, servers.size))
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                if (servers.isEmpty()) {
                    ListEmptyState(
                        title = stringResource(R.string.mcp_empty_title),
                        summary = stringResource(R.string.mcp_empty_summary),
                        icon = Icons.Rounded.Hub,
                        action = {
                            TextButton(
                                text = stringResource(R.string.mcp_add_server),
                                onClick = { showAdd = true },
                            )
                        },
                    )
                } else {
                    servers.forEach { server ->
                        ArrowPreference(
                            title = server.name,
                            summary = server.lastError?.takeIf(String::isNotBlank)?.let { "连接异常 · ${it.take(90)}" }
                                ?: if (server.transportType == McpTransportType.STDIO) {
                                    "STDIO · ${server.activeTools.size}/${server.tools.size} 个工具"
                                } else {
                                    stringResource(
                                        R.string.mcp_server_row_summary,
                                        server.activeTools.size,
                                        server.tools.size,
                                    )
                                },
                            onClick = { onNavigate(AppRoute.McpServerDetail(server.id)) },
                        )
                    }
                }
            }
        }
        item(key = "portable") {
            SmallTitle("批量迁移")
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                DetailListRow(
                    title = "导入 MCP 连接",
                    subtitle = "读取 eta-mcp-connections/v1 JSON；令牌和本机凭据不会随文件导入",
                    icon = Icons.Rounded.Upload,
                    value = if (batchWorking) "处理中" else "选择 JSON",
                    enabled = !batchWorking,
                    onClick = {
                        importLauncher.launch(
                            arrayOf("application/json", "text/plain", "application/octet-stream"),
                        )
                    },
                )
                DetailListRow(
                    title = "导出 MCP 连接",
                    subtitle = "仅导出名称、地址、协议、认证类型和工具偏好，不导出 Bearer Token",
                    icon = Icons.Rounded.Download,
                    value = if (servers.isEmpty()) "暂无连接" else "${servers.size} 个连接",
                    enabled = servers.isNotEmpty() && !batchWorking,
                    onClick = { exportLauncher.launch("eta-mcp-connections.json") },
                )
            }
        }
    }

    WindowDialog(
        show = showAdd,
        title = stringResource(R.string.mcp_add_server),
        onDismissRequest = { if (!working) showAdd = false },
    ) {
        // 只有表单滚动，按钮固定在底部：输入法弹出或错误较长时确认操作仍然可达。
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.mcp_server_name),
                    singleLine = true,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        text = "HTTP",
                        onClick = { transportType = McpTransportType.HTTP },
                        enabled = !working,
                    )
                    TextButton(
                        text = "STDIO",
                        onClick = { transportType = McpTransportType.STDIO },
                        enabled = !working,
                    )
                    Text(
                        text = if (transportType == McpTransportType.HTTP) "远程或本机 HTTP" else "启动本地子进程",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
                if (transportType == McpTransportType.HTTP) {
                    TextField(
                        value = url,
                        onValueChange = { url = it },
                        label = stringResource(R.string.mcp_server_url),
                        singleLine = true,
                        enabled = !working,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = token,
                        onValueChange = { token = it },
                        label = stringResource(R.string.mcp_bearer_optional),
                        singleLine = true,
                        enabled = !working,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = launchCommand,
                        onValueChange = { launchCommand = it },
                        label = "本机服务启动命令（可选）",
                        singleLine = true,
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    TextField(
                        value = stdioCommand,
                        onValueChange = { stdioCommand = it },
                        label = "stdio 命令或可执行文件",
                        singleLine = true,
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = stdioArguments,
                        onValueChange = { stdioArguments = it },
                        label = "参数（每行一个）",
                        enabled = !working,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                TextField(
                    value = workingDirectory,
                    onValueChange = { workingDirectory = it },
                    label = "工作目录（可选）",
                    singleLine = true,
                    enabled = !working,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        text = it,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
            }
            MiuixDialogActions(
                modifier = Modifier.padding(top = 16.dp),
                confirmText = if (working) {
                    stringResource(R.string.mcp_connecting)
                } else {
                    stringResource(R.string.mcp_add_server)
                },
                confirmEnabled = !working,
                onCancel = { showAdd = false },
                onConfirm = {
                    val normalizedName = name.trim()
                    val normalizedUrl = url.trim()
                    error = when {
                        normalizedName.isBlank() -> resources.getString(R.string.mcp_name_required)
                        transportType == McpTransportType.HTTP -> runCatching { validateMcpEndpoint(normalizedUrl) }
                            .exceptionOrNull()?.message
                        stdioCommand.isBlank() -> "请填写 MCP stdio 启动命令"
                        else -> null
                    }
                    if (error == null) scope.launch {
                        working = true
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val draft = McpServerSetting(
                                    id = UUID.randomUUID().toString(),
                                    name = normalizedName,
                                    url = normalizedUrl,
                                    transportType = transportType,
                                    protocolMode = McpProtocolMode.AUTO,
                                    authorizationType = if (token.isBlank()) {
                                        McpAuthorizationType.NONE
                                    } else {
                                        McpAuthorizationType.BEARER
                                    },
                                    stdioCommand = stdioCommand.trim(),
                                    stdioArguments = stdioArguments.lineSequence()
                                        .map(String::trim)
                                        .filter(String::isNotBlank)
                                        .toList(),
                                    launchCommand = launchCommand.trim(),
                                    workingDirectory = workingDirectory.trim(),
                                )
                                val discovered = McpServerManager.discover(draft, token)
                                McpServerRepository.add(discovered, token)
                            }
                        }
                        working = false
                        result.onSuccess {
                            showAdd = false
                            name = ""
                            url = ""
                            token = ""
                            stdioCommand = ""
                            stdioArguments = ""
                            launchCommand = ""
                            workingDirectory = ""
                            error = null
                            Toast.makeText(
                                context,
                                resources.getString(R.string.mcp_server_added, it.tools.size),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }.onFailure {
                            error = it.message ?: resources.getString(R.string.mcp_connection_failed)
                        }
                    }
                },
            )
        }
    }
}

private fun readBoundedMcpConfig(context: Context, uri: Uri): String {
    val input = context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
    input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (output.size() + count > McpPortableCodec.MAX_BYTES) {
                error("MCP 配置文件超过 256 KiB 上限")
            }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}

@Composable
internal fun McpServerDetailScreen(
    serverId: String,
    onBack: () -> Unit,
    onOpenCatalog: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val servers by McpServerRepository.serversFlow().collectAsState(initial = emptyList())
    val server = servers.firstOrNull { it.id == serverId }
    var working by remember { mutableStateOf(false) }
    var pendingRiskyTool by remember { mutableStateOf<McpToolDefinition?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var tokenError by remember(serverId) { mutableStateOf<String?>(null) }
    var credentialStatus by remember(serverId) { mutableStateOf<McpCredentialStatus?>(null) }
    var debugTool by remember(serverId) { mutableStateOf<McpToolDefinition?>(null) }
    var debugArgs by remember(serverId) { mutableStateOf("{}") }
    var debugResult by remember(serverId) { mutableStateOf<String?>(null) }
    var debugWorking by remember { mutableStateOf(false) }

    LaunchedEffect(server, working) {
        if (working) return@LaunchedEffect
        credentialStatus = null
        if (server?.authorizationType == McpAuthorizationType.BEARER) {
            credentialStatus = withContext(Dispatchers.IO) { McpServerRepository.credentialStatus(serverId) }
        }
    }

    MiuixScaffoldPage(
        title = server?.name ?: stringResource(R.string.route_mcp_server_detail),
        onBack = onBack,
        actions = {
            IconButton(
                enabled = server != null && !working,
                onClick = {
                    scope.launch {
                        working = true
                        val result = withContext(Dispatchers.IO) {
                            runCatching { McpServerManager.refresh(serverId) }
                        }
                        working = false
                        Toast.makeText(
                            context,
                            result.fold(
                                onSuccess = { resources.getString(R.string.mcp_refreshed, it.tools.size) },
                                onFailure = { it.message ?: resources.getString(R.string.mcp_refresh_failed) },
                            ),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.mcp_refresh_tools),
                )
            }
        },
    ) {
        if (server == null) {
            item(key = "missing") {
                ListEmptyState(title = stringResource(R.string.mcp_server_missing))
            }
            return@MiuixScaffoldPage
        }
        item(key = "server") {
            SmallTitle(stringResource(R.string.mcp_server_settings))
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                SwitchPreference(
                    title = stringResource(R.string.mcp_enable_server),
                    summary = if (server.transportType == McpTransportType.STDIO) {
                        "STDIO · ${server.stdioCommand}"
                    } else {
                        "HTTP · ${server.url}"
                    },
                    checked = server.enabled,
                    onCheckedChange = { enabled ->
                        scope.launch(Dispatchers.IO) {
                            McpServerRepository.update(server.copy(enabled = enabled))
                            if (!enabled) McpServerManager.stopLocal(server.id)
                        }
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.mcp_update_token),
                    summary = stringResource(
                        when {
                            server.authorizationType == McpAuthorizationType.NONE -> R.string.mcp_no_authentication
                            credentialStatus == McpCredentialStatus.UNAVAILABLE -> R.string.mcp_credentials_unavailable
                            credentialStatus == McpCredentialStatus.MISSING -> R.string.mcp_credentials_missing
                            credentialStatus == McpCredentialStatus.AVAILABLE -> R.string.mcp_token_configured
                            else -> R.string.mcp_credentials_checking
                        }
                    ),
                    onClick = { if (!working) { tokenError = null; showToken = true } },
                )
                ArrowPreference(
                    title = stringResource(R.string.mcp_browse_resources),
                    summary = stringResource(R.string.mcp_catalog_not_loaded),
                    onClick = onOpenCatalog,
                )
                DetailListRow(
                    title = "连接诊断",
                    subtitle = buildString {
                        append(if (server.transportType == McpTransportType.STDIO) "STDIO" else "HTTP")
                        append(" · ").append(server.lastProtocolVersion ?: "尚未握手")
                        server.lastError?.takeIf(String::isNotBlank)?.let { append(" · ").append(it.take(120)) }
                    },
                    value = when {
                        !server.enabled -> "已停用"
                        server.lastError != null -> "需处理"
                        server.lastHealthyAt != null -> "正常"
                        else -> "未检测"
                    },
                    icon = Icons.Rounded.Hub,
                    onClick = {
                        if (!working) scope.launch {
                            working = true
                            runCatching { McpServerManager.refresh(serverId) }
                            working = false
                        }
                    },
                )
            }
        }
        item(key = "tools") {
            SmallTitle(stringResource(R.string.mcp_tools_count, server.tools.size))
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                if (server.tools.isEmpty()) {
                    ListEmptyState(
                        title = stringResource(R.string.mcp_no_tools),
                        summary = stringResource(R.string.mcp_refresh_tools_hint),
                        icon = Icons.Rounded.Refresh,
                    )
                } else {
                    server.tools.forEach { tool ->
                        val checked = tool.name in server.enabledToolNames
                        ArrowPreference(
                            title = stringResource(R.string.mcp_debug_tool),
                            summary = tool.name,
                            enabled = checked && !working,
                            onClick = { debugTool = tool; debugArgs = "{}"; debugResult = null },
                        )
                        SwitchPreference(
                            title = tool.title.ifBlank { tool.name },
                            summary = toolSummary(tool),
                            checked = checked,
                            onCheckedChange = { enabled ->
                                if (enabled && tool.readOnlyHint != true) {
                                    pendingRiskyTool = tool
                                } else {
                                    scope.launch(Dispatchers.IO) {
                                        McpServerRepository.setToolEnabled(serverId, tool.name, enabled)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        item(key = "delete") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(top = 4.dp, bottom = 12.dp),
            ) {
                BasicComponent(
                    title = stringResource(R.string.mcp_delete_server),
                    titleColor = BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.error),
                    summary = stringResource(R.string.mcp_delete_server_summary),
                    onClick = { showDelete = true },
                )
            }
        }
    }

    pendingRiskyTool?.let { tool ->
        WindowDialog(
            show = true,
            title = stringResource(R.string.mcp_enable_risky_tool),
            summary = stringResource(R.string.mcp_enable_risky_tool_summary, tool.name, server?.name.orEmpty()),
            onDismissRequest = { pendingRiskyTool = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.mcp_enable_tool),
                destructive = true,
                onCancel = { pendingRiskyTool = null },
                onConfirm = {
                    scope.launch(Dispatchers.IO) {
                        McpServerRepository.setToolEnabled(serverId, tool.name, true)
                    }
                    pendingRiskyTool = null
                },
            )
        }
    }

    debugTool?.let { tool ->
        WindowDialog(
            show = true,
            title = stringResource(R.string.mcp_debug_tool),
            summary = tool.name,
            onDismissRequest = { if (!debugWorking) debugTool = null },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(
                    value = debugArgs,
                    onValueChange = { debugArgs = it.take(64_000) },
                    label = stringResource(R.string.mcp_debug_arguments),
                    enabled = !debugWorking,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                )
                debugResult?.let { result ->
                    Text(result, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
                MiuixDialogActions(
                    confirmText = stringResource(if (debugWorking) R.string.mcp_debug_running else R.string.mcp_debug_call),
                    confirmEnabled = !debugWorking,
                    onCancel = { if (!debugWorking) debugTool = null },
                    onConfirm = {
                        val current = debugTool ?: return@MiuixDialogActions
                        val parsed = runCatching { JSONObject(debugArgs) }.getOrElse {
                            debugResult = resources.getString(R.string.mcp_debug_invalid_json)
                            return@MiuixDialogActions
                        }
                        scope.launch {
                            debugWorking = true
                            debugResult = runCatching {
                                withContext(Dispatchers.IO) { McpServerManager.callTool(serverId, current.name, parsed) }
                            }.fold(
                                onSuccess = { it.toString(2).take(8_000) },
                                onFailure = { it.message ?: resources.getString(R.string.mcp_debug_failed) },
                            )
                            debugWorking = false
                        }
                    },
                )
            }
        }
    }

    WindowDialog(
        show = showToken,
        title = stringResource(R.string.mcp_update_token),
        summary = stringResource(R.string.mcp_update_token_summary),
        onDismissRequest = { if (!working) { showToken = false; token = ""; tokenError = null } },
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                TextField(
                    value = token,
                    onValueChange = { token = it },
                    label = stringResource(R.string.mcp_bearer_token),
                    singleLine = true,
                    enabled = !working,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                tokenError?.let { message ->
                    Text(text = message, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error)
                }
            }
            MiuixDialogActions(
                modifier = Modifier.padding(top = 16.dp),
                confirmText = stringResource(if (working) R.string.page_saving_d70d42 else R.string.action_save),
                cancelEnabled = !working,
                confirmEnabled = !working,
                onCancel = { if (!working) { showToken = false; token = ""; tokenError = null } },
                onConfirm = {
                    server?.let { current ->
                        val enteredToken = token
                        scope.launch {
                            working = true
                            tokenError = null
                            try {
                                withContext(Dispatchers.IO) {
                                    McpServerRepository.update(
                                        current.copy(
                                            authorizationType = if (enteredToken.isBlank()) {
                                                McpAuthorizationType.NONE
                                            } else {
                                                McpAuthorizationType.BEARER
                                            },
                                        ),
                                        bearerToken = enteredToken,
                                    )
                                }
                                token = ""
                                showToken = false
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: Exception) {
                                tokenError = failure.message?.take(500) ?: resources.getString(R.string.mcp_connection_failed)
                            } finally {
                                working = false
                            }
                        }
                    }
                },
            )
        }
    }


    WindowDialog(
        show = showDelete,
        title = stringResource(R.string.mcp_delete_server),
        summary = stringResource(R.string.mcp_delete_confirm, server?.name.orEmpty()),
        onDismissRequest = { showDelete = false },
    ) {
        MiuixDialogActions(
            confirmText = stringResource(R.string.ui_delete_3755f5),
            destructive = true,
            onCancel = { showDelete = false },
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) { McpServerRepository.delete(serverId) }
                    onBack()
                }
            },
        )
    }
}

@Composable
private fun toolSummary(tool: McpToolDefinition): String = when {
    tool.readOnlyHint == true -> tool.description.ifBlank { stringResource(R.string.mcp_read_only_tool) }
    else -> tool.description.ifBlank { stringResource(R.string.mcp_may_modify_data) }
}
