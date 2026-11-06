package io.github.mangi.eta.ui.screens.terminal

import android.content.ClipData
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileMove
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.FileToolException
import io.github.mangi.eta.ui.app.WorkspaceEntry
import io.github.mangi.eta.ui.app.WorkspaceFileStore
import io.github.mangi.eta.ui.app.WorkspacePreview
import io.github.mangi.eta.ui.components.EtaMonospace
import io.github.mangi.eta.ui.components.EtaPreferenceGroupItem
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun WorkspaceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val store = remember(context.applicationContext) { WorkspaceFileStore(context) }
    val scope = rememberCoroutineScope()
    var path by rememberSaveable { mutableStateOf("") }
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<WorkspaceEntry>?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<WorkspaceMessage?>(null) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var sortField by rememberSaveable { mutableStateOf(WorkspaceSortField.NAME) }
    var descending by rememberSaveable { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var createFolderName by remember { mutableStateOf("") }
    var selectedEntry by remember { mutableStateOf<WorkspaceEntry?>(null) }
    var renameTarget by remember { mutableStateOf<WorkspaceEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceEntry?>(null) }
    var previewTarget by remember { mutableStateOf<WorkspaceEntry?>(null) }
    var clipboard by remember { mutableStateOf<WorkspaceClipboard?>(null) }
    var publicAccess by remember { mutableStateOf(Environment.isExternalStorageManager()) }

    fun reportError(error: Throwable) {
        if (error is CancellationException) throw error
        message = WorkspaceMessage(workspaceErrorMessage(resources, error), isError = true)
    }

    fun perform(success: String, clearClipboard: Boolean = false, operation: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                operation()
                if (clearClipboard) clipboard = null
                message = WorkspaceMessage(success, isError = false)
                revision++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                reportError(error)
            } finally {
                busy = false
            }
        }
    }

    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        publicAccess = Environment.isExternalStorageManager()
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true
            try {
                var succeeded = 0
                uris.forEach { if (store.importFile(it)) succeeded++ }
                message = if (succeeded == uris.size) {
                    WorkspaceMessage(resources.getString(R.string.capability_workspace_imported), isError = false)
                } else {
                    WorkspaceMessage(
                        resources.getQuantityString(R.plurals.capability_workspace_partial_import, succeeded, succeeded),
                        isError = true,
                    )
                }
                if (succeeded > 0) path = "imports"
                revision++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                reportError(error)
            } finally {
                busy = false
            }
        }
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val source = pendingExport
        pendingExport = null
        if (uri != null && source != null) {
            perform(resources.getString(R.string.capability_workspace_exported)) {
                store.exportFile(source, uri)
            }
        }
    }

    LaunchedEffect(path) { entries = null }
    LaunchedEffect(path, revision) {
        try {
            entries = store.list(path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            entries = emptyList()
            reportError(error)
        }
    }

    val visibleEntries = remember(entries, searchText, sortField, descending) {
        val terms = searchText.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotBlank)
        val filtered = entries.orEmpty().filter { entry ->
            terms.isEmpty() || terms.all { term ->
                entry.name.lowercase(Locale.ROOT).contains(term) || entry.path.lowercase(Locale.ROOT).contains(term)
            }
        }
        val fieldComparator = when (sortField) {
            WorkspaceSortField.NAME -> compareBy<WorkspaceEntry> { it.name.lowercase(Locale.ROOT) }
            WorkspaceSortField.SIZE -> compareBy<WorkspaceEntry> { it.size }
            WorkspaceSortField.KIND -> compareBy<WorkspaceEntry> { if (it.directory) 0 else 1 }
        }.thenBy { it.name.lowercase(Locale.ROOT) }
        val directed = if (descending) fieldComparator.reversed() else fieldComparator
        filtered.sortedWith(compareByDescending<WorkspaceEntry> { it.directory }.then(directed))
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.capability_workspace),
        onBack = onBack,
        actions = {
            IconButton(onClick = { createFolderName = ""; showCreateFolder = true }, enabled = !busy) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.capability_workspace_new_folder))
            }
            IconButton(onClick = { showSort = true }) {
                Icon(Icons.Rounded.Sort, contentDescription = stringResource(R.string.capability_workspace_sort))
            }
        },
    ) {
        item(key = "workspace-info") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp, bottom = 12.dp),
            ) {
                BasicComponent(
                    title = stringResource(R.string.capability_workspace_private),
                    summary = stringResource(R.string.capability_workspace_private_summary),
                )
            }
        }
        item(key = "search") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                TextField(
                    value = searchText,
                    onValueChange = { searchText = it.take(MAX_SEARCH_CHARS) },
                    label = stringResource(R.string.capability_workspace_search),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
            }
        }
        item(key = "actions") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.capability_workspace_import),
                    enabled = !busy,
                    endActions = { if (busy) InfiniteProgressIndicator(size = 20.dp) },
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                )
                ArrowPreference(
                    title = stringResource(R.string.capability_workspace_public),
                    summary = if (publicAccess) {
                        stringResource(R.string.capability_workspace_public_granted)
                    } else {
                        stringResource(R.string.capability_workspace_public_summary)
                    },
                    onClick = {
                        try {
                            accessLauncher.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        } catch (error: android.content.ActivityNotFoundException) {
                            reportError(error)
                        }
                    },
                )
            }
        }
        message?.let { current ->
            item(key = "message") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    BasicComponent(
                        title = current.text,
                        titleColor = BasicComponentDefaults.titleColor(
                            color = if (current.isError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onBackground,
                        ),
                        endActions = {
                            IconButton(onClick = { message = null }) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.action_close),
                                    modifier = Modifier.size(18.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                )
                            }
                        },
                    )
                }
            }
        }
        clipboard?.let { pending ->
            item(key = "clipboard") {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                ) {
                    ArrowPreference(
                        title = stringResource(
                            if (pending.move) R.string.capability_workspace_move_pending else R.string.capability_workspace_copy_pending,
                            pending.entry.name,
                        ),
                        summary = stringResource(R.string.capability_workspace_paste_here),
                        enabled = !busy,
                        startAction = {
                            Icon(
                                if (pending.move) Icons.Rounded.DriveFileMove else Icons.Rounded.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp).size(24.dp),
                                tint = MiuixTheme.colorScheme.primary,
                            )
                        },
                        endActions = {
                            IconButton(onClick = { clipboard = null }) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = stringResource(R.string.action_cancel),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                        onClick = {
                            perform(
                                resources.getString(
                                    if (pending.move) R.string.capability_workspace_moved else R.string.capability_workspace_copied,
                                ),
                                clearClipboard = true,
                            ) {
                                if (pending.move) store.moveInto(pending.entry.path, path)
                                else store.copyInto(pending.entry.path, path)
                            }
                        },
                    )
                }
            }
        }
        item(key = "path") {
            SmallTitle(if (path.isBlank()) stringResource(R.string.capability_workspace_files) else path)
        }
        val currentEntries = entries
        val hasParent = path.isNotBlank()
        if (hasParent) {
            item(key = "parent") {
                EtaPreferenceGroupItem(isFirst = true, isLast = visibleEntries.isEmpty()) {
                    ArrowPreference(
                        title = stringResource(R.string.capability_workspace_parent),
                        startAction = {
                            Icon(
                                Icons.Rounded.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp).size(24.dp),
                                tint = MiuixTheme.colorScheme.primary,
                            )
                        },
                        onClick = {
                            searchText = ""
                            path = path.substringBeforeLast('/', "")
                        },
                    )
                }
            }
        }
        when {
            currentEntries == null -> item(key = "loading") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) { InfiniteProgressIndicator(size = 24.dp) }
            }
            visibleEntries.isEmpty() -> item(key = "empty") {
                ListEmptyState(
                    title = stringResource(
                        if (searchText.isBlank()) R.string.capability_workspace_empty else R.string.capability_workspace_no_results,
                    ),
                    summary = stringResource(
                        if (searchText.isBlank()) R.string.capability_workspace_empty_summary else R.string.capability_workspace_search_summary,
                    ),
                    icon = if (searchText.isBlank()) Icons.Rounded.FolderOpen else Icons.Rounded.SearchOff,
                )
            }
            else -> itemsIndexed(visibleEntries, key = { _, entry -> entry.path }) { index, entry ->
                EtaPreferenceGroupItem(
                    isFirst = index == 0 && !hasParent,
                    isLast = index == visibleEntries.lastIndex,
                ) {
                    ArrowPreference(
                        title = entry.name,
                        summary = if (entry.directory) {
                            stringResource(R.string.capability_workspace_directory)
                        } else {
                            stringResource(
                                R.string.capability_workspace_file_summary,
                                Formatter.formatShortFileSize(context, entry.size),
                            )
                        },
                        startAction = {
                            Icon(
                                if (entry.directory) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp).size(24.dp),
                                tint = if (entry.directory) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        },
                        endActions = {
                            IconButton(onClick = { selectedEntry = entry }, enabled = !busy) {
                                Icon(
                                    Icons.Rounded.MoreVert,
                                    contentDescription = stringResource(R.string.capability_workspace_more_actions),
                                    modifier = Modifier.size(20.dp),
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                )
                            }
                        },
                        enabled = !busy,
                        onClick = {
                            if (entry.directory) {
                                searchText = ""
                                path = entry.path
                            } else {
                                previewTarget = entry
                            }
                        },
                    )
                }
            }
        }
    }

    selectedEntry?.let { entry ->
        WorkspaceActionDialog(
            entry = entry,
            onDismiss = { selectedEntry = null },
            onPreview = { previewTarget = entry },
            onExport = {
                pendingExport = entry.path
                exportLauncher.launch(entry.name)
            },
            onShare = {
                scope.launch {
                    busy = true
                    try {
                        val uri = store.prepareShare(entry.path)
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = store.mimeType(entry.path)
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri(entry.name, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(
                            Intent.createChooser(send, resources.getString(R.string.capability_workspace_share_chooser)),
                        )
                    } catch (error: Throwable) {
                        reportError(error)
                    } finally {
                        busy = false
                    }
                }
            },
            onRename = { renameTarget = entry },
            onCopy = {
                clipboard = WorkspaceClipboard(entry, move = false)
                message = WorkspaceMessage(resources.getString(R.string.capability_workspace_copy_ready), isError = false)
            },
            onMove = {
                clipboard = WorkspaceClipboard(entry, move = true)
                message = WorkspaceMessage(resources.getString(R.string.capability_workspace_move_ready), isError = false)
            },
            onDelete = { deleteTarget = entry },
        )
    }

    renameTarget?.let { entry ->
        WorkspaceNameDialog(
            title = stringResource(R.string.action_rename),
            label = stringResource(R.string.capability_workspace_name),
            initial = entry.name,
            confirm = stringResource(R.string.action_rename),
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                renameTarget = null
                perform(resources.getString(R.string.capability_workspace_renamed)) {
                    store.rename(entry.path, name)
                }
            },
        )
    }

    if (showCreateFolder) {
        WorkspaceNameDialog(
            title = stringResource(R.string.capability_workspace_new_folder),
            label = stringResource(R.string.capability_workspace_name),
            initial = createFolderName,
            confirm = stringResource(R.string.capability_workspace_create),
            onDismiss = { showCreateFolder = false },
            onConfirm = { name ->
                showCreateFolder = false
                perform(resources.getString(R.string.capability_workspace_folder_created)) {
                    store.createDirectory(path, name)
                }
            },
        )
    }

    deleteTarget?.let { entry ->
        WindowDialog(
            show = true,
            title = stringResource(R.string.capability_workspace_delete_title, entry.name),
            summary = stringResource(
                if (entry.directory) R.string.capability_workspace_delete_folder_summary
                else R.string.capability_workspace_delete_file_summary,
            ),
            onDismissRequest = { deleteTarget = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_delete),
                destructive = true,
                onCancel = { deleteTarget = null },
                onConfirm = {
                    deleteTarget = null
                    perform(resources.getString(R.string.capability_workspace_deleted)) {
                        store.delete(entry.path)
                    }
                },
            )
        }
    }

    previewTarget?.let { entry ->
        WorkspacePreviewDialog(
            entry = entry,
            store = store,
            onDismiss = { previewTarget = null },
        )
    }

    if (showSort) {
        WorkspaceSortDialog(
            field = sortField,
            descending = descending,
            onField = { sortField = it },
            onDirection = { descending = !descending },
            onDismiss = { showSort = false },
        )
    }
}

@Composable
private fun WorkspaceActionDialog(
    entry: WorkspaceEntry,
    onDismiss: () -> Unit,
    onPreview: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    WindowDialog(
        show = true,
        title = entry.name,
        summary = entry.path,
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (!entry.directory) {
                WorkspaceActionRow(stringResource(R.string.capability_workspace_preview), Icons.Rounded.Visibility, onDismiss, onPreview)
                WorkspaceActionRow(stringResource(R.string.capability_workspace_export), Icons.AutoMirrored.Rounded.InsertDriveFile, onDismiss, onExport)
                WorkspaceActionRow(stringResource(R.string.capability_workspace_share), Icons.Rounded.Share, onDismiss, onShare)
            }
            WorkspaceActionRow(stringResource(R.string.action_rename), Icons.Rounded.Edit, onDismiss, onRename)
            WorkspaceActionRow(stringResource(R.string.capability_workspace_copy), Icons.Rounded.ContentCopy, onDismiss, onCopy)
            WorkspaceActionRow(stringResource(R.string.capability_workspace_move), Icons.Rounded.DriveFileMove, onDismiss, onMove)
            WorkspaceActionRow(
                stringResource(R.string.action_delete),
                Icons.Rounded.DeleteOutline,
                onDismiss,
                onDelete,
                destructive = true,
            )
        }
    }
}

@Composable
private fun WorkspaceActionRow(
    title: String,
    icon: ImageVector,
    dismiss: () -> Unit,
    action: () -> Unit,
    destructive: Boolean = false,
) {
    ArrowPreference(
        title = title,
        startAction = {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.padding(end = 10.dp).size(22.dp),
                tint = if (destructive) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
            )
        },
        titleColor = BasicComponentDefaults.titleColor(
            color = if (destructive) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onBackground,
        ),
        onClick = {
            dismiss()
            action()
        },
    )
}

@Composable
private fun WorkspaceNameDialog(
    title: String,
    label: String,
    initial: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(initial) { mutableStateOf(initial) }
    WindowDialog(show = true, title = title, onDismissRequest = onDismiss) {
        Column {
            TextField(
                value = value,
                onValueChange = { value = it.take(MAX_NAME_CHARS) },
                label = label,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            MiuixDialogActions(
                confirmText = confirm,
                confirmEnabled = value.trim().isNotEmpty(),
                onCancel = onDismiss,
                onConfirm = { onConfirm(value) },
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun WorkspacePreviewDialog(
    entry: WorkspaceEntry,
    store: WorkspaceFileStore,
    onDismiss: () -> Unit,
) {
    var preview by remember(entry.path) { mutableStateOf<WorkspacePreview?>(null) }
    var failed by remember(entry.path) { mutableStateOf(false) }
    LaunchedEffect(entry.path) {
        try {
            preview = store.preview(entry.path)
        } catch (_: Throwable) {
            failed = true
        }
    }
    WindowDialog(
        show = true,
        title = entry.name,
        summary = Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, entry.size),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            val current = preview
            when {
                failed -> Text(
                    stringResource(R.string.capability_workspace_preview_failed),
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.body2,
                )
                current == null -> Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) { InfiniteProgressIndicator(size = 24.dp) }
                current.binary -> Text(
                    stringResource(R.string.capability_workspace_preview_binary),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                )
                else -> SelectionContainer {
                    Text(
                        text = current.text.ifEmpty { stringResource(R.string.capability_workspace_preview_empty) },
                        style = MiuixTheme.textStyles.body2.copy(fontFamily = EtaMonospace),
                        color = MiuixTheme.colorScheme.onBackground,
                    )
                }
            }
            if (current?.truncated == true) {
                Text(
                    text = stringResource(R.string.capability_workspace_preview_truncated),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun WorkspaceSortDialog(
    field: WorkspaceSortField,
    descending: Boolean,
    onField: (WorkspaceSortField) -> Unit,
    onDirection: () -> Unit,
    onDismiss: () -> Unit,
) {
    WindowDialog(
        show = true,
        title = stringResource(R.string.capability_workspace_sort),
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            WorkspaceSortRow(stringResource(R.string.capability_workspace_sort_name), field == WorkspaceSortField.NAME) {
                onField(WorkspaceSortField.NAME)
            }
            WorkspaceSortRow(stringResource(R.string.capability_workspace_sort_size), field == WorkspaceSortField.SIZE) {
                onField(WorkspaceSortField.SIZE)
            }
            WorkspaceSortRow(stringResource(R.string.capability_workspace_sort_kind), field == WorkspaceSortField.KIND) {
                onField(WorkspaceSortField.KIND)
            }
            ArrowPreference(
                title = stringResource(
                    if (descending) R.string.capability_workspace_sort_descending
                    else R.string.capability_workspace_sort_ascending,
                ),
                onClick = onDirection,
            )
        }
    }
}

@Composable
private fun WorkspaceSortRow(label: String, selected: Boolean, onClick: () -> Unit) {
    ArrowPreference(
        title = label,
        summary = if (selected) stringResource(R.string.capability_workspace_sort_selected) else null,
        titleColor = BasicComponentDefaults.titleColor(
            color = if (selected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onBackground,
        ),
        onClick = onClick,
    )
}

private fun workspaceErrorMessage(resources: Resources, error: Throwable): String = when {
    error is FileToolException && error.code == "DESTINATION_EXISTS" ->
        resources.getString(R.string.capability_workspace_conflict)
    error.message == "WORKSPACE_ROOT_PROTECTED" ->
        resources.getString(R.string.capability_workspace_root_protected)
    error.message == "WORKSPACE_NAME_INVALID" || error.message == "WORKSPACE_NAME_TOO_LONG" ->
        resources.getString(R.string.capability_workspace_invalid_name)
    error is FileToolException && error.code in setOf("COPY_LIMIT", "SYMLINK_UNSUPPORTED", "UNSUPPORTED_FILE_TYPE") ->
        resources.getString(R.string.capability_workspace_operation_limited)
    error is IOException -> resources.getString(R.string.capability_workspace_failed)
    else -> resources.getString(R.string.capability_workspace_failed)
}

private data class WorkspaceMessage(val text: String, val isError: Boolean)
private data class WorkspaceClipboard(val entry: WorkspaceEntry, val move: Boolean)
private enum class WorkspaceSortField { NAME, SIZE, KIND }

private const val MAX_NAME_CHARS = 255
private const val MAX_SEARCH_CHARS = 512
