package io.github.mangi.eta.ui.screens.a2a

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.fork.a2a.A2AAgentEntity
import io.github.mangi.eta.fork.a2a.A2AAgentRepository
import io.github.mangi.eta.fork.a2a.A2AInboundConfigStore
import io.github.mangi.eta.fork.a2a.A2AInboundService
import io.github.mangi.eta.fork.a2a.A2AArtifactEntity
import io.github.mangi.eta.fork.a2a.A2AArtifactQuarantineState
import io.github.mangi.eta.fork.a2a.A2AHttpClient
import io.github.mangi.eta.fork.a2a.A2APeerPolicy
import io.github.mangi.eta.fork.a2a.A2ATaskEntity
import io.github.mangi.eta.fork.a2a.a2aPeerPolicyForManualEndpoint
import io.github.mangi.eta.fork.a2a.isLocalEndpointHost
import io.github.mangi.eta.agent.device.LocalNetworkPermission
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** A2A peer registry. Remote agents remain behind explicit host allowlisting and quarantine. */
@Composable
internal fun A2ASettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context.applicationContext) {
        A2AAgentRepository(
            context = context.applicationContext,
            client = A2AHttpClient(A2APeerPolicy(allowedHosts = emptySet())),
        )
    }
    val inboundStore = remember(context.applicationContext) { A2AInboundConfigStore(context.applicationContext) }
    val inbound by inboundStore.observe().collectAsState(initial = inboundStore.snapshot())
    val agents by repository.observeAgents().collectAsState(initial = emptyList())
    val tasks by repository.observeTasks().collectAsState(initial = emptyList())
    var showAddDialog by remember { mutableStateOf(false) }
    var displayName by remember { mutableStateOf("") }
    var cardUrl by remember { mutableStateOf("") }
    var bearerToken by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showInboundToken by remember { mutableStateOf(false) }
    var inboundPermissionError by remember { mutableStateOf<String?>(null) }
    val enableInbound = {
        inboundStore.setEnabled(true)
        runCatching { A2AInboundService.start(context.applicationContext) }
            .onFailure { failure ->
                inboundStore.setEnabled(false)
                inboundStore.setLastError(failure.message ?: "无法启动 A2A 服务端")
            }
    }
    val localNetworkPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            inboundPermissionError = null
            enableInbound()
        } else {
            inboundPermissionError = "需要“本地网络”权限，其他设备才能访问 Eta。"
        }
    }

    MiuixScaffoldPage(
        title = "A2A Agent",
        onBack = onBack,
        actions = {
            IconButton(
                enabled = !busy,
                onClick = {
                    error = null
                    showAddDialog = true
                },
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "添加 A2A Agent")
            }
        },
        overlay = {
            WindowDialog(
                show = showAddDialog,
                title = "添加 A2A Agent",
                onDismissRequest = { if (!busy) showAddDialog = false },
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = "显示名称（可留空，使用 Agent Card 名称）",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = cardUrl,
                        onValueChange = { cardUrl = it.trim() },
                        label = "Agent Card URL（HTTPS 或局域网 HTTP）",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextField(
                        value = bearerToken,
                        onValueChange = { bearerToken = it },
                        label = "Bearer Token（可选，存入 Android Keystore）",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "公网地址必须使用 HTTPS。私网 HTTP 仅允许你明确填写的精确局域网主机，避免开放任意内网地址。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    error?.let {
                        Text(it, color = StatusError, style = MiuixTheme.textStyles.footnote1)
                    }
                    MiuixDialogActions(
                        confirmText = if (busy) "发现中…" else "发现并添加",
                        onCancel = { if (!busy) showAddDialog = false },
                        onConfirm = {
                            if (busy) return@MiuixDialogActions
                            val parsed = runCatching { cardUrl.toHttpUrl() }.getOrNull()
                            if (parsed == null ||
                                (parsed.scheme != "https" &&
                                    !(parsed.scheme == "http" && isLocalEndpointHost(parsed.host)))
                            ) {
                                error = "请输入 HTTPS 地址，或使用明确局域网主机的 HTTP 地址"
                                return@MiuixDialogActions
                            }
                            busy = true
                            error = null
                            scope.launch {
                                runCatching {
                                    A2AAgentRepository(
                                        context = context.applicationContext,
                                        client = A2AHttpClient(a2aPeerPolicyForManualEndpoint(cardUrl)),
                                    ).discoverAndAddAgent(
                                        displayName = displayName,
                                        cardUrl = cardUrl,
                                        bearerToken = bearerToken.takeIf(String::isNotBlank),
                                    )
                                }.onSuccess {
                                    showAddDialog = false
                                    displayName = ""
                                    cardUrl = ""
                                    bearerToken = ""
                                }.onFailure {
                                    error = it.message ?: "Agent Card 发现失败"
                                }
                                busy = false
                            }
                        },
                    )
                }
            }
        },
    ) {
        item(key = "a2a_intro") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Text(
                    text = "使用 A2A 1.0 JSON-RPC 双向连接外部 Agent。Eta 可主动调用已添加的 Agent，也可作为受 Token 保护的局域网服务端接受请求。远端产物默认隔离，需明确批准。",
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        item(key = "inbound_title") { SmallTitle("本机作为 A2A 服务端") }
        item(key = "inbound_service") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "允许其他设备调用这台手机",
                                style = MiuixTheme.textStyles.body1,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = if (inbound.enabled) "前台监听中 · 端口 ${inbound.port}" else "关闭 · 不监听任何端口",
                                style = MiuixTheme.textStyles.footnote2,
                                color = if (inbound.enabled) StatusSuccess else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Switch(
                            checked = inbound.enabled,
                            onCheckedChange = { enabled ->
                                inboundPermissionError = null
                                if (enabled) {
                                    when (LocalNetworkPermission.accessState(context)) {
                                        LocalNetworkPermission.AccessState.NOT_REQUIRED,
                                        LocalNetworkPermission.AccessState.GRANTED,
                                        -> enableInbound()
                                        LocalNetworkPermission.AccessState.DENIED ->
                                            localNetworkPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                                    }
                                } else {
                                    inboundStore.setEnabled(false)
                                    runCatching { A2AInboundService.stop(context.applicationContext) }
                                }
                            },
                        )
                    }
                    inboundPermissionError?.let { Text(it, color = StatusError, style = MiuixTheme.textStyles.footnote1) }
                    inbound.lastError?.let { Text("服务端错误：$it", color = StatusError, style = MiuixTheme.textStyles.footnote1) }
                    Text(
                        text = "入站请求只能进行模型对话，终端、文件、设备操作和 Subagent 工具均关闭。请只在你信任的局域网中使用。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (inbound.enabled) {
                        inbound.agentCardUrl?.let { url ->
                            CopyableEndpoint(label = "Agent Card", value = url)
                        }
                        inbound.endpointUrl?.let { url ->
                            CopyableEndpoint(label = "JSON-RPC", value = url)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "Bearer Token",
                                style = MiuixTheme.textStyles.footnote1,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                text = if (showInboundToken) "隐藏" else "显示",
                                onClick = { showInboundToken = !showInboundToken },
                            )
                            TextButton(
                                text = "复制",
                                onClick = { copyText(context, "Eta A2A Token", inbound.token) },
                            )
                            TextButton(
                                text = "轮换",
                                onClick = { inboundStore.rotateToken() },
                            )
                        }
                        Text(
                            text = if (showInboundToken) inbound.token else "••••••••••••••••••••••••",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    } else {
                        Text(
                            text = "开启后会生成仅存放于 Android Keystore 的 Bearer Token。",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
        item(key = "agent_title") { SmallTitle("已连接 Agent") }
        if (agents.isEmpty()) {
            item(key = "agent_empty") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ListEmptyState(
                        title = "尚未添加 A2A Agent",
                        summary = "先取得对方的 Agent Card 地址，再通过右上角添加。",
                        icon = Icons.Rounded.CloudQueue,
                        action = {
                            TextButton(text = "添加 Agent", onClick = { showAddDialog = true })
                        },
                    )
                }
            }
        } else {
            items(agents, key = { it.id }) { agent ->
                A2AAgentCard(
                    agent = agent,
                    onRefresh = {
                        scope.launch {
                            runCatching {
                                A2AAgentRepository(
                                    context = context.applicationContext,
                                    client = A2AHttpClient(a2aPeerPolicyForManualEndpoint(agent.cardUrl)),
                                ).refreshAgentCard(agent.id)
                            }
                        }
                    },
                    onDelete = { scope.launch { repository.removeAgent(agent.id) } },
                )
            }
        }
        item(key = "tasks_title") { SmallTitle("远端任务与产物") }
        if (tasks.isEmpty()) {
            item(key = "tasks_empty") {
                Text(
                    text = "还没有 A2A 任务。可从定时任务或后续会议室参与者发起。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        } else {
            items(tasks.take(20), key = { it.localId }) { task ->
                A2ATaskCard(task = task, repository = repository)
            }
        }
    }
}

@Composable
private fun CopyableEndpoint(label: String, value: String) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MiuixTheme.textStyles.footnote1, fontWeight = FontWeight.Medium)
            Text(
                text = value,
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(text = "复制", onClick = { copyText(context, label, value) })
    }
}

private fun copyText(context: android.content.Context, label: String, value: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText(label, value))
}

@Composable
private fun A2AAgentCard(
    agent: A2AAgentEntity,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = agent.displayName,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${agent.endpointUrl}\nA2A ${agent.protocolVersion} · ${agent.trustState}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (agent.enabled) StatusSuccess else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新 Agent Card")
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = "删除 Agent",
                    tint = StatusError,
                )
            }
        }
    }
}

@Composable
private fun A2ATaskCard(task: A2ATaskEntity, repository: A2AAgentRepository) {
    val artifacts by repository.observeArtifacts(task.localId).collectAsState(initial = emptyList())
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = task.remoteTaskId ?: task.localId,
                style = MiuixTheme.textStyles.body1,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = task.state +
                    (task.errorMessage?.let { " · $it" } ?: "") +
                    if (task.cancelUnconfirmed) " · 取消未确认" else "",
                style = MiuixTheme.textStyles.footnote2,
                color = when {
                    task.errorCode != null || task.cancelUnconfirmed -> StatusError
                    task.state.contains("COMPLETED") -> StatusSuccess
                    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (artifacts.isNotEmpty()) {
                artifacts.forEach { artifact ->
                    A2AArtifactRow(task.localId, artifact, repository)
                }
            }
        }
    }
}

@Composable
private fun A2AArtifactRow(
    taskId: String,
    artifact: A2AArtifactEntity,
    repository: A2AAgentRepository,
) {
    val scope = rememberCoroutineScope()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artifact.name ?: artifact.artifactId,
                style = MiuixTheme.textStyles.footnote1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${artifact.mediaTypeSummary ?: "未知类型"} · ${artifact.quarantineState}",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        TextButton(
            text = "拒绝",
            onClick = {
                scope.launch {
                    repository.setArtifactQuarantineState(
                        taskId,
                        artifact.artifactId,
                        A2AArtifactQuarantineState.REJECTED,
                    )
                }
            },
        )
        TextButton(
            text = "批准",
            onClick = {
                scope.launch {
                    repository.setArtifactQuarantineState(
                        taskId,
                        artifact.artifactId,
                        A2AArtifactQuarantineState.APPROVED,
                    )
                }
            },
        )
    }
}
