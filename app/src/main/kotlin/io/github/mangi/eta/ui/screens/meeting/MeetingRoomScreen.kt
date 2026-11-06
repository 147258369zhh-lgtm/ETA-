package io.github.mangi.eta.ui.screens.meeting

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import android.text.format.DateFormat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.fork.meeting.MeetingAuthorType
import io.github.mangi.eta.fork.meeting.MeetingComposerState
import io.github.mangi.eta.fork.meeting.MeetingMessageEntity
import io.github.mangi.eta.fork.meeting.MeetingMentionRouter
import io.github.mangi.eta.fork.meeting.MeetingParticipantEntity
import io.github.mangi.eta.fork.meeting.MeetingRepository
import io.github.mangi.eta.fork.meeting.MeetingRoomStatus
import io.github.mangi.eta.fork.meeting.MeetingRoomUiState
import io.github.mangi.eta.fork.meeting.MeetingRoutingDecision
import io.github.mangi.eta.fork.meeting.MeetingRunCoordinator
import io.github.mangi.eta.fork.meeting.MeetingSettingsStore
import io.github.mangi.eta.fork.meeting.MeetingTaskEntity
import io.github.mangi.eta.fork.meeting.MeetingTaskStatus
import io.github.mangi.eta.fork.meeting.toUiState
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.EtaChoiceChip
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffold
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusIdle
import io.github.mangi.eta.ui.components.StatusRunning
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.StatusWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun MeetingRoomScreen(
    roomId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit = onBack,
    onArchived: () -> Unit = onBack,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snapshot by MeetingRepository.snapshot(context, roomId)
        .collectAsState(initial = null)
    val state = snapshot?.toUiState()

    MiuixScaffold(
        title = state?.room?.title ?: stringResource(R.string.meeting_room_title),
        onBack = onBack,
        modifier = modifier,
        actions = {
            state?.let {
                MeetingStatusBadge(status = it.room.status)
            }
        },
    ) { padding, _, sidePadding ->
        if (state == null) {
            MeetingRoomLoading(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = sidePadding),
            )
        } else {
            MeetingRoomContent(
                state = state,
                onDeleted = onDeleted,
                onArchived = onArchived,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding())
                    .padding(horizontal = sidePadding)
                    .imePadding(),
            )
        }
    }
}

/** Main-chat meeting stage. It deliberately has no scaffold so Home owns navigation and the side pane. */
@Composable
internal fun MeetingRoomConversationStage(
    roomId: String,
    onDeleted: () -> Unit = {},
    onArchived: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snapshot by MeetingRepository.snapshot(context, roomId)
        .collectAsState(initial = null)
    val state = snapshot?.toUiState()
    if (state == null) {
        MeetingRoomLoading(modifier = modifier.fillMaxSize())
    } else {
        MeetingRoomContent(
            state = state,
            onDeleted = onDeleted,
            onArchived = onArchived,
            modifier = modifier
                .fillMaxSize()
                .imePadding(),
        )
    }
}

@Composable
private fun MeetingRoomContent(
    state: MeetingRoomUiState,
    onDeleted: () -> Unit,
    onArchived: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val settingsStore = remember(context.applicationContext) {
        MeetingSettingsStore.get(context.applicationContext)
    }
    val meetingSettings by settingsStore.settings.collectAsState()
    var input by rememberSaveable(state.room.id) { mutableStateOf("") }
    var selectedParticipantName by rememberSaveable(state.room.id) { mutableStateOf<String?>(null) }
    var showParticipantDetails by rememberSaveable(state.room.id) { mutableStateOf(false) }
    var hasPositionedTimeline by remember(state.room.id) { mutableStateOf(false) }
    var followNextTimelineChange by remember(state.room.id) { mutableStateOf(false) }
    var managementOpen by rememberSaveable(state.room.id) { mutableStateOf(false) }
    var renameOpen by rememberSaveable(state.room.id) { mutableStateOf(false) }
    var deleteOpen by rememberSaveable(state.room.id) { mutableStateOf(false) }
    var exportTitle by rememberSaveable(state.room.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/markdown"),
    ) { uri ->
        val title = exportTitle
        exportTitle = null
        if (uri == null || title == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val snapshot = MeetingRepository.snapshot(context, state.room.id).first()
                    ?: error("会议室不存在")
                val markdown = MeetingMarkdownExporter.export(snapshot)
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error("无法打开导出文件")
                withContext(Dispatchers.IO) {
                    output.use { it.write(markdown.toByteArray(Charsets.UTF_8)) }
                }
            }.onSuccess {
                Toast.makeText(context, "会议室已导出", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Toast.makeText(
                    context,
                    "导出失败：${error.message ?: error.javaClass.simpleName}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    val activeMention = MeetingComposerState.activeMention(input, input.length)
    val mentionMatches = remember(activeMention?.query, state.mentionCandidates) {
        val query = activeMention?.query.orEmpty()
        state.mentionCandidates.filter { participant ->
            query.isBlank() || participant.profileName.contains(query, ignoreCase = true) ||
                participant.displayName.contains(query, ignoreCase = true)
        }
    }
    val latestSummary = state.latestSummary
    val timeline = state.messages.filterNot { it.id == latestSummary?.id }
    val timelineItemCount = timeline.size + if (latestSummary == null) 0 else 1
    val isRunning = state.isRunning
    val routing = remember(input, state.mentionCandidates) {
        input.takeIf(String::isNotBlank)?.let { text ->
            MeetingMentionRouter.route(
                text = text,
                availableProfiles = state.mentionCandidates.map(MeetingParticipantEntity::profileName),
                maxParticipants = meetingSettings.maxParallelParticipants,
            )
        }
    }
    val selectedParticipant = state.participants.firstOrNull {
        it.profileName == selectedParticipantName
    } ?: state.host ?: state.participants.firstOrNull()

    LaunchedEffect(timelineItemCount, latestSummary?.id) {
        if (timelineItemCount > 0) {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            val lastVisibleIndex = visibleItems.lastOrNull()?.index ?: -1
            val lastIndex = timelineItemCount - 1
            val nearBottom = visibleItems.isEmpty() || lastVisibleIndex >= lastIndex - 1
            if (!hasPositionedTimeline || followNextTimelineChange ||
                (meetingSettings.autoFollowMessages && nearBottom)
            ) {
                listState.animateScrollToItem(lastIndex)
            }
            hasPositionedTimeline = true
        }
        followNextTimelineChange = false
    }

    Column(modifier = modifier) {
        MeetingRoomOverview(
            state = state,
            selectedParticipant = selectedParticipant,
            onParticipantClick = { participant ->
                selectedParticipantName = participant.profileName
                showParticipantDetails = true
            },
            onOpenParticipantDetails = { showParticipantDetails = true },
            onOpenManagement = { managementOpen = true },
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (timelineItemCount == 0) {
                ListEmptyState(
                    title = stringResource(R.string.meeting_room_no_messages),
                    summary = stringResource(R.string.meeting_room_topology_summary),
                    icon = Icons.Rounded.Groups,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 8.dp,
                        bottom = 10.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(timeline, key = MeetingMessageEntity::id) { message ->
                        MeetingMessageBubble(
                            message = message,
                            task = message.authorProfile?.let(state::latestTaskFor),
                            roomStatus = state.room.status,
                        )
                    }
                    latestSummary?.let { summary ->
                        item(key = "meeting-summary-${summary.id}") {
                            MeetingSummaryCard(
                                summary = summary,
                                previewLines = meetingSettings.summaryPreviewLines,
                            )
                        }
                    }
                }
            }
        }
        if (activeMention != null && mentionMatches.isNotEmpty()) {
            MentionSuggestions(
                participants = mentionMatches,
                onSelect = { participant ->
                    input = MeetingComposerState.completeMention(
                        text = input,
                        active = activeMention,
                        profileName = participant.profileName,
                    )
                },
            )
        }
        routing?.let { decision ->
            MeetingRoutePreview(
                decision = decision,
                maxParallelParticipants = meetingSettings.maxParallelParticipants,
                autoDispatchEnabled = meetingSettings.autoDispatchWithoutMention,
            )
        }
        MeetingComposer(
            value = input,
            enabled = !isRunning,
            submitting = isRunning,
            canSend = routing !is MeetingRoutingDecision.UnknownMentions,
            onValueChange = { input = it.take(32_000) },
            onSend = {
                val message = input.trim()
                if (message.isNotEmpty() && !isRunning) {
                    if (MeetingRunCoordinator.start(context, state.room.id, message)) {
                        input = ""
                        followNextTimelineChange = true
                    }
                }
            },
        )
    }

    if (showParticipantDetails) selectedParticipant?.let { participant ->
        MeetingParticipantDialog(
            participant = participant,
            modelLabel = participant.modelDisplayLabel(providers),
            task = state.latestTaskFor(participant.profileName),
            latestActivity = state.latestActivityFor(participant.profileName),
            roomStatus = state.room.status,
            onMention = if (participant.enabled) {
                {
                    showParticipantDetails = false
                    selectedParticipantName = null
                    input = if (input.isBlank()) {
                        "@${participant.profileName} "
                    } else {
                        input.trimEnd() + " @${participant.profileName} "
                    }
                }
            } else {
                null
            },
            onDismiss = { showParticipantDetails = false },
        )
    }

    if (managementOpen) {
        MeetingRoomManagementDialog(
            roomTitle = state.room.title,
            isRunning = state.isRunning,
            onRename = {
                managementOpen = false
                renameOpen = true
            },
            onExport = {
                managementOpen = false
                exportTitle = state.room.title
                exportLauncher.launch(MeetingMarkdownExporter.defaultFileName(state.room.title))
            },
            onArchive = {
                managementOpen = false
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            MeetingRepository.setRoomArchived(context, state.room.id, archived = true)
                        }
                    }.onSuccess {
                        Toast.makeText(context, "会议室已归档", Toast.LENGTH_SHORT).show()
                        onArchived()
                    }.onFailure { error ->
                        Toast.makeText(
                            context,
                            "归档失败：${error.message ?: error.javaClass.simpleName}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
            onDelete = {
                managementOpen = false
                deleteOpen = true
            },
            onDismiss = { managementOpen = false },
        )
    }

    if (renameOpen) {
        MeetingRoomRenameDialog(
            roomId = state.room.id,
            currentTitle = state.room.title,
            onDismiss = { renameOpen = false },
        )
    }

    if (deleteOpen) {
        MeetingRoomDeleteDialog(
            roomId = state.room.id,
            roomTitle = state.room.title,
            onDismiss = { deleteOpen = false },
            onDeleted = {
                deleteOpen = false
                onDeleted()
            },
        )
    }
}

@Composable
private fun MeetingRoomManagementDialog(
    roomTitle: String,
    isRunning: Boolean,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    WindowDialog(
        show = true,
        title = roomTitle,
        summary = "管理会议室与导出完整记录",
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ArrowPreference(
                title = "重命名",
                startAction = {
                    MeetingManageActionIcon(
                        icon = Icons.Rounded.Edit,
                        tint = MiuixTheme.colorScheme.primary,
                    )
                },
                onClick = onRename,
            )
            ArrowPreference(
                title = "导出完整记录",
                summary = "Markdown，包含参与者、对话与 Agent 任务",
                startAction = {
                    MeetingManageActionIcon(
                        icon = Icons.Rounded.Download,
                        tint = StatusSuccess,
                    )
                },
                onClick = onExport,
            )
            ArrowPreference(
                title = "归档会议室",
                summary = if (isRunning) {
                    "本轮运行结束后可归档"
                } else {
                    "从活动列表移到归档，记录仍可查看和导出"
                },
                startAction = {
                    MeetingManageActionIcon(icon = Icons.Rounded.Archive, tint = StatusWarning)
                },
                onClick = onArchive,
                enabled = !isRunning,
            )
            ArrowPreference(
                title = "删除会议室",
                summary = if (isRunning) "本轮运行结束后可删除" else "会同时删除全部消息与任务记录",
                startAction = {
                    MeetingManageActionIcon(icon = Icons.Rounded.DeleteOutline, tint = StatusError)
                },
                onClick = onDelete,
                enabled = !isRunning,
            )
        }
    }
}

@Composable
private fun MeetingManageActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .squircleSurface(tint.copy(alpha = 0.12f), 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
    }
}

@Composable
internal fun MeetingRoomRenameDialog(
    roomId: String,
    currentTitle: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var title by rememberSaveable(roomId) { mutableStateOf(currentTitle) }
    WindowDialog(
        show = true,
        title = "重命名会议室",
        onDismissRequest = onDismiss,
    ) {
        Column {
            TextField(
                value = title,
                onValueChange = { title = it.take(120) },
                label = "会议室名称",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            MiuixDialogActions(
                confirmText = "保存",
                confirmEnabled = title.isNotBlank(),
                onCancel = onDismiss,
                onConfirm = {
                    val normalized = title.trim()
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                MeetingRepository.renameRoom(context, roomId, normalized)
                            }
                        }.onSuccess {
                            onDismiss()
                        }.onFailure { error ->
                            Toast.makeText(
                                context,
                                "重命名失败：${error.message ?: "未知错误"}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                },
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
internal fun MeetingRoomDeleteDialog(
    roomId: String,
    roomTitle: String,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    WindowDialog(
        show = true,
        title = "删除“$roomTitle”？",
        summary = "会议室的全部消息、Agent 任务和事件记录都会被删除，此操作无法撤销。建议先导出记录。",
        onDismissRequest = onDismiss,
    ) {
        MiuixDialogActions(
            confirmText = "删除",
            onCancel = onDismiss,
            onConfirm = {
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            MeetingRepository.deleteRoom(context, roomId)
                        }
                    }.onSuccess {
                        onDeleted()
                    }.onFailure { error ->
                        Toast.makeText(
                            context,
                            "删除失败：${error.message ?: "未知错误"}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            },
        )
    }
}

@Composable
private fun MeetingRoomOverview(
    state: MeetingRoomUiState,
    selectedParticipant: MeetingParticipantEntity?,
    onParticipantClick: (MeetingParticipantEntity) -> Unit,
    onOpenParticipantDetails: () -> Unit,
    onOpenManagement: () -> Unit,
) {
    val selectedTask = selectedParticipant?.let { state.latestTaskFor(it.profileName) }
    val selectedActivity = selectedParticipant?.let { state.latestActivityFor(it.profileName) }
    val completedTasks = state.latestRoundTasks.count { it.status == MeetingTaskStatus.COMPLETED }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .squircleSurface(MiuixTheme.colorScheme.surfaceContainer, 16.dp)
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.35f),
                cornerRadius = 16.dp,
            )
            .padding(horizontal = 11.dp, vertical = 9.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MeetingStatusDot(status = state.room.status)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meetingStatusLabel(state.room.status),
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = meetingStatusHint(state),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = if (state.latestRoundTasks.isEmpty()) {
                    "${state.mentionCandidates.size} 位 Agent"
                } else {
                    "本轮 $completedTasks/${state.latestRoundTasks.size}"
                },
                style = MiuixTheme.textStyles.footnote2,
                color = when {
                    state.isFailed -> MiuixTheme.colorScheme.error
                    state.isRunning -> MiuixTheme.colorScheme.primary
                    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
            IconButton(onClick = onOpenManagement, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Rounded.MoreHoriz,
                    contentDescription = "会议室管理",
                    modifier = Modifier.size(19.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            items(state.participants, key = MeetingParticipantEntity::profileName) { participant ->
                ParticipantChip(
                    participant = participant,
                    selected = participant.profileName == selectedParticipant?.profileName,
                    task = state.latestTaskFor(participant.profileName),
                    roomStatus = state.room.status,
                    onClick = { onParticipantClick(participant) },
                )
            }
        }
        selectedParticipant?.let { participant ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 7.dp)
                    .squircleSurface(MiuixTheme.colorScheme.surface.copy(alpha = 0.58f), 12.dp)
                    .clickable(onClick = onOpenParticipantDetails)
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MeetingProcessDot(task = selectedTask, roomStatus = state.room.status)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${participant.displayName} · ${participantProcessLabel(participant, selectedTask, state.room.status)}",
                        style = MiuixTheme.textStyles.footnote2,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = participantProcessPreview(participant, selectedTask, selectedActivity),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "查看进程",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ParticipantChip(
    participant: MeetingParticipantEntity,
    selected: Boolean,
    task: MeetingTaskEntity?,
    roomStatus: String,
    onClick: () -> Unit,
) {
    val isHost = participant.isHost
    val background = when {
        selected -> MiuixTheme.colorScheme.primaryContainer
        isHost -> MiuixTheme.colorScheme.secondaryContainer
        else -> MiuixTheme.colorScheme.surface
    }
    val foreground = when {
        selected -> MiuixTheme.colorScheme.onPrimaryContainer
        isHost -> MiuixTheme.colorScheme.onSecondaryContainer
        else -> MiuixTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .squircleSurface(background, cornerRadius = 16.dp)
            .squircleBorder(
                width = if (selected) 1.dp else 0.5.dp,
                color = if (selected) {
                    MiuixTheme.colorScheme.primary.copy(alpha = 0.65f)
                } else {
                    MiuixTheme.colorScheme.outline.copy(alpha = 0.38f)
                },
                cornerRadius = 16.dp,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MeetingAgentAvatar(
            displayName = participant.displayName,
            task = task,
            roomStatus = roomStatus,
            isHost = isHost,
            selected = selected,
            size = 22.dp,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = participant.displayName,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = if (selected || isHost) FontWeight.SemiBold else FontWeight.Normal,
            color = foreground,
        )
        if (isHost) {
            Text(
                text = stringResource(R.string.meeting_room_moderator_badge),
                style = MiuixTheme.textStyles.footnote2,
                color = foreground.copy(alpha = 0.7f),
                modifier = Modifier.padding(start = 5.dp),
            )
        } else if (!participant.enabled) {
            Text(
                text = "停用",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 5.dp),
            )
        }
    }
}

@Composable
private fun MeetingAgentAvatar(
    displayName: String,
    task: MeetingTaskEntity?,
    roomStatus: String,
    isHost: Boolean = false,
    selected: Boolean = false,
    size: androidx.compose.ui.unit.Dp,
) {
    val palette = listOf(
        MiuixTheme.colorScheme.primary,
        MiuixTheme.colorScheme.secondary,
        MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
    val accent = palette[(displayName.hashCode() and Int.MAX_VALUE) % palette.size]
    val statusColor = meetingProcessColor(task, roomStatus, isHost)
    val statusDescription = meetingProcessLabel(task, roomStatus, isHost)
    Box(
        modifier = Modifier
            .size(size + 4.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(
                        accent.copy(alpha = if (selected) 0.18f else 0.08f),
                        accent.copy(alpha = 0.01f),
                    ),
                ),
            )
            .semantics { contentDescription = "$displayName，$statusDescription" },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(size)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            accent.copy(alpha = if (selected) 0.34f else 0.22f),
                            MiuixTheme.colorScheme.surface.copy(alpha = 0.35f),
                        ),
                    ),
                )
                .border(
                    width = if (selected) 1.dp else 0.5.dp,
                    color = accent.copy(alpha = if (selected) 0.8f else 0.36f),
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = displayName.trim().take(1).uppercase().ifBlank { "A" },
                style = MiuixTheme.textStyles.footnote2,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(9.dp)
                .squircleSurface(MiuixTheme.colorScheme.surface, 5.dp)
                .padding(1.5.dp)
                .squircleSurface(statusColor, 4.dp),
        )
    }
}

private fun meetingStatusHint(state: MeetingRoomUiState): String = when {
    state.isRunning && state.completingTasks > 0 ->
        "${state.completingTasks} 位 Agent 正在独立执行，主持人监督汇总"
    state.isRunning -> "主持人正在选择参与者或汇总本轮结果"
    state.isFailed -> "本轮未完成，可查看 Agent 进程后重试"
    state.isWaiting -> "结果已就绪，可 @ Agent 或交给主持人分派下一轮"
    else -> "@ 一位直接执行，@ 多位并行执行"
}

private fun participantProcessLabel(
    participant: MeetingParticipantEntity,
    task: MeetingTaskEntity?,
    roomStatus: String,
): String = when (task?.status) {
    MeetingTaskStatus.QUEUED -> "排队等待执行"
    MeetingTaskStatus.RUNNING -> "正在独立执行"
    MeetingTaskStatus.COMPLETED -> "本轮任务已完成"
    MeetingTaskStatus.FAILED -> "本轮任务失败"
    MeetingTaskStatus.CANCELLED -> "本轮任务已取消"
    else -> when {
        participant.isHost && roomStatus == MeetingRoomStatus.RUNNING -> "正在监督并协调本轮"
        participant.isHost && roomStatus == MeetingRoomStatus.FAILED -> "主持人进程已中断"
        participant.isHost -> "等待下一轮，持续监督"
        else -> "本轮尚未分派任务"
    }
}

private fun participantProcessPreview(
    participant: MeetingParticipantEntity,
    task: MeetingTaskEntity?,
    activity: MeetingMessageEntity?,
): String {
    val value = when {
        !participant.enabled -> "该 Agent 已停用，不再参与新任务；历史进程仍可查看"
        task?.status == MeetingTaskStatus.FAILED -> task.errorMessage
        activity?.content?.isNotBlank() == true -> activity.content
        task?.instruction?.isNotBlank() == true -> task.instruction
        else -> participant.profileSummary
    }.orEmpty()
    return value.replace(Regex("\\s+"), " ").trim().ifBlank {
        if (participant.isHost) "负责选择参与者、监督并行任务并汇总结果" else "等待任务分派"
    }
}

@Composable
private fun MeetingProcessDot(task: MeetingTaskEntity?, roomStatus: String) {
    val color = meetingProcessColor(task, roomStatus)
    Box(
        modifier = Modifier
            .size(7.dp)
            .squircleSurface(color, 7.dp)
            .semantics { contentDescription = meetingProcessLabel(task, roomStatus) },
    )
}

private fun meetingProcessLabel(
    task: MeetingTaskEntity?,
    roomStatus: String,
    isHost: Boolean = false,
): String = when (task?.status) {
    MeetingTaskStatus.COMPLETED -> "已完成"
    MeetingTaskStatus.FAILED -> "执行失败"
    MeetingTaskStatus.CANCELLED -> "已取消"
    MeetingTaskStatus.QUEUED -> "排队中"
    MeetingTaskStatus.RUNNING -> "执行中"
    else -> if (isHost && roomStatus == MeetingRoomStatus.RUNNING) "主持人正在协调" else "空闲"
}

@Composable
private fun taskStatusColor(status: String) = when (status) {
    MeetingTaskStatus.COMPLETED -> StatusSuccess
    MeetingTaskStatus.FAILED -> StatusError
    MeetingTaskStatus.CANCELLED -> StatusIdle
    MeetingTaskStatus.QUEUED -> StatusWarning
    MeetingTaskStatus.RUNNING -> StatusRunning
    else -> StatusIdle
}

@Composable
private fun meetingProcessColor(
    task: MeetingTaskEntity?,
    roomStatus: String,
    isHost: Boolean = false,
): Color = when (task?.status) {
    MeetingTaskStatus.COMPLETED -> StatusSuccess
    MeetingTaskStatus.FAILED -> StatusError
    MeetingTaskStatus.CANCELLED -> StatusIdle
    MeetingTaskStatus.QUEUED -> StatusWarning
    MeetingTaskStatus.RUNNING -> StatusRunning
    else -> if (isHost && roomStatus == MeetingRoomStatus.RUNNING) StatusRunning else StatusIdle
}

@Composable
private fun MeetingTaskPill(task: MeetingTaskEntity) {
    val color = taskStatusColor(task.status)
    Row(
        modifier = Modifier
            .squircleSurface(MiuixTheme.colorScheme.surface.copy(alpha = 0.62f), 11.dp)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .squircleSurface(color, 6.dp),
        )
        Text(
            text = task.participantProfile,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = when (task.status) {
                MeetingTaskStatus.QUEUED -> "排队"
                MeetingTaskStatus.RUNNING -> "执行中"
                MeetingTaskStatus.COMPLETED -> "完成"
                MeetingTaskStatus.FAILED -> "失败"
                MeetingTaskStatus.CANCELLED -> "取消"
                else -> task.status
            },
            style = MiuixTheme.textStyles.footnote2,
            color = color,
        )
    }
}

@Composable
private fun MeetingStatusDot(status: String) {
    val running = status == MeetingRoomStatus.RUNNING
    val transition = rememberInfiniteTransition(label = "meeting_status")
    val alpha by transition.animateFloat(
        initialValue = if (running) 0.35f else 1f,
        targetValue = if (running) 1f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 760),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "meeting_status_alpha",
    )
    val color = when (status) {
        MeetingRoomStatus.FAILED -> MiuixTheme.colorScheme.error
        MeetingRoomStatus.RUNNING -> MiuixTheme.colorScheme.primary
        MeetingRoomStatus.WAITING_FOR_USER -> MiuixTheme.colorScheme.onTertiaryContainer
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .graphicsLayer { this.alpha = alpha }
            .squircleSurface(color, 8.dp),
    )
}

@Composable
private fun MeetingStatusBadge(status: String) {
    Row(
        modifier = Modifier
            .padding(end = 8.dp)
            .squircleSurface(MiuixTheme.colorScheme.surfaceContainer, 14.dp)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MeetingStatusDot(status)
        Text(
            text = meetingStatusLabel(status),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun MeetingSummaryCard(summary: MeetingMessageEntity, previewLines: Int) {
    var expanded by rememberSaveable(summary.id) { mutableStateOf(false) }
    val canExpand = previewLines != 0
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .squircleSurface(MiuixTheme.colorScheme.tertiaryContainer, 15.dp)
                .clickable(enabled = canExpand) { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (expanded) Icons.Rounded.Groups else Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.meeting_room_summary_title),
                    style = MiuixTheme.textStyles.footnote1,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onTertiaryContainer,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (canExpand) {
                        if (expanded) "收起" else "展开"
                    } else {
                        summary.authorName
                    },
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (expanded || previewLines != 0) {
                SelectionContainer {
                    Text(
                        text = summary.content,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.9f),
                        maxLines = if (expanded || previewLines == 0) Int.MAX_VALUE else previewLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MeetingMessageBubble(
    message: MeetingMessageEntity,
    task: MeetingTaskEntity?,
    roomStatus: String,
) {
    val isUser = message.authorType == MeetingAuthorType.USER
    val isSystem = message.authorType == MeetingAuthorType.SYSTEM
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.86f else 0.92f)
                .squircleSurface(
                    color = when {
                        isUser -> MiuixTheme.colorScheme.primaryContainer
                        isSystem -> MiuixTheme.colorScheme.errorContainer
                        else -> MiuixTheme.colorScheme.surfaceContainer
                    },
                    cornerRadius = 16.dp,
                )
                .padding(horizontal = 13.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isUser) {
                    MeetingAgentAvatar(
                        displayName = message.authorName,
                        task = task,
                        roomStatus = roomStatus,
                        size = 22.dp,
                    )
                    Spacer(Modifier.width(7.dp))
                }
                if (isSystem) {
                    Icon(
                        imageVector = Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = MiuixTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    text = message.authorName,
                    style = MiuixTheme.textStyles.footnote2,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        isUser -> MiuixTheme.colorScheme.onPrimaryContainer
                        isSystem -> MiuixTheme.colorScheme.onErrorContainer
                        else -> MiuixTheme.colorScheme.primary
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = DateFormat.getTimeFormat(LocalContext.current).format(message.createdAt),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            SelectionContainer {
                Text(
                    text = message.content,
                    style = MiuixTheme.textStyles.body2,
                    color = when {
                        isUser -> MiuixTheme.colorScheme.onPrimaryContainer
                        isSystem -> MiuixTheme.colorScheme.onErrorContainer
                        else -> MiuixTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun MentionSuggestions(
    participants: List<MeetingParticipantEntity>,
    onSelect: (MeetingParticipantEntity) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(participants, key = MeetingParticipantEntity::profileName) { participant ->
            EtaChoiceChip(
                text = "@${participant.profileName}",
                selected = false,
                onClick = { onSelect(participant) },
            )
        }
    }
}

@Composable
private fun MeetingRoutePreview(
    decision: MeetingRoutingDecision,
    maxParallelParticipants: Int,
    autoDispatchEnabled: Boolean,
) {
    val unknown = decision is MeetingRoutingDecision.UnknownMentions
    val hostBlocked = decision is MeetingRoutingDecision.HostDispatch && !autoDispatchEnabled
    val warning = unknown || hostBlocked
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .squircleSurface(
                if (warning) MiuixTheme.colorScheme.errorContainer
                else MiuixTheme.colorScheme.surfaceContainer,
                11.dp,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            imageVector = if (warning) Icons.Rounded.Warning else Icons.AutoMirrored.Rounded.Send,
            contentDescription = null,
            tint = if (warning) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = when (decision) {
                is MeetingRoutingDecision.Direct -> "直接执行 · @${decision.participant}"
                is MeetingRoutingDecision.Parallel ->
                    "独立并行 · ${decision.participants.joinToString(" + ") { "@$it" }}"
                is MeetingRoutingDecision.HostDispatch ->
                    if (autoDispatchEnabled) {
                        "未指定参与者 · 主持人将选择 1–$maxParallelParticipants 位 Agent"
                    } else {
                        "当前设置要求明确执行者 · 请输入 @Agent"
                    }
                is MeetingRoutingDecision.UnknownMentions ->
                    "无法发送 · 未识别 ${decision.mentions.joinToString(" ") { "@$it" }}"
            },
            style = MiuixTheme.textStyles.footnote2,
            color = if (warning) MiuixTheme.colorScheme.onErrorContainer else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MeetingComposer(
    value: String,
    enabled: Boolean,
    submitting: Boolean,
    canSend: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .navigationBarsPadding()
            .squircleSurface(MiuixTheme.colorScheme.surfaceContainer, 20.dp)
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                cornerRadius = 20.dp,
            )
            .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            label = stringResource(R.string.meeting_room_mention_hint),
            enabled = enabled,
            maxLines = 5,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            text = if (submitting) {
                stringResource(R.string.meeting_room_running)
            } else {
                stringResource(R.string.meeting_room_send)
            },
            onClick = onSend,
            enabled = enabled && canSend && value.isNotBlank(),
        )
    }
}

@Composable
private fun MeetingParticipantDialog(
    participant: MeetingParticipantEntity,
    modelLabel: String,
    task: MeetingTaskEntity?,
    latestActivity: MeetingMessageEntity?,
    roomStatus: String,
    onMention: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    WindowDialog(
        show = true,
        title = participant.displayName,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (participant.isHost) "主持人 / 监督器" else "会议参与者",
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.primary,
            )
            ParticipantDetailRow("Profile", "@${participant.profileName}")
            ParticipantDetailRow("模型", modelLabel)
            ParticipantDetailRow(
                "执行进程",
                participantProcessLabel(participant, task, roomStatus),
            )
            if (!participant.enabled) {
                ParticipantDetailRow("状态", "已停用，不接收新任务；历史进程仍保留")
            }
            task?.let { currentTask ->
                ParticipantDetailRow("本轮指令", currentTask.instruction.take(6_000))
                currentTask.errorMessage?.takeIf(String::isNotBlank)?.let { error ->
                    ParticipantDetailRow("错误", error)
                }
            }
            latestActivity?.content?.takeIf(String::isNotBlank)?.let { output ->
                ParticipantDetailRow(
                    if (participant.isHost) "最新汇总" else "最新结果",
                    output.take(8_000),
                )
            }
            if (participant.profileSummary.isNotBlank()) {
                ParticipantDetailRow("职责", participant.profileSummary)
            }
            Text(
                text = if (participant.isHost) {
                    "主持人负责选择参与者、监督并行任务并汇总结果；用户明确 @ 时由用户决定执行者。"
                } else {
                    "参与者只接收本轮独立任务，不自动继承其他参与者的上下文。"
                },
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    text = if (onMention == null) "当前已停用" else "加入 @ 指令",
                    onClick = onMention ?: {},
                    enabled = onMention != null,
                )
                TextButton(text = "关闭", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun ParticipantDetailRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        SelectionContainer {
            Text(
                text = value,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun MeetingParticipantEntity.modelDisplayLabel(providers: List<ProviderSetting>): String {
    val modelId = modelId?.trim().orEmpty()
    if (modelId.isEmpty()) return "自动选择 · Profile → 全局 Subagent → 主模型"
    val provider = providerId?.let { expected -> providers.firstOrNull { it.id == expected } }
        ?: providers.firstOrNull { candidate -> candidate.models.any { it.id == modelId } }
    val model = provider?.models?.firstOrNull { it.id == modelId }
    return when {
        provider != null && model != null -> "${provider.name} · ${model.displayName.ifBlank { model.modelId }}"
        providerId == null -> providers.firstNotNullOfOrNull { candidate ->
            candidate.models.firstOrNull { it.id == modelId }?.let { "${candidate.name} · ${it.displayName.ifBlank { it.modelId }}" }
        } ?: modelId
        else -> "指定模型当前不可用 · $modelId"
    }
}

@Composable
private fun MeetingRoomLoading(modifier: Modifier = Modifier) {
    ListEmptyState(
        title = stringResource(R.string.meeting_room_loading),
        icon = Icons.Rounded.Groups,
        modifier = modifier,
    )
}
