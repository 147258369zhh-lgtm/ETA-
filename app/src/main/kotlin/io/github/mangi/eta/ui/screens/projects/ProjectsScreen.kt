package io.github.mangi.eta.ui.screens.projects

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.fork.projects.ProjectDraft
import io.github.mangi.eta.fork.projects.ProjectEntity
import io.github.mangi.eta.fork.projects.ProjectException
import io.github.mangi.eta.fork.projects.ProjectRepository
import io.github.mangi.eta.fork.projects.ProjectSubjectType
import io.github.mangi.eta.ui.app.WorkspaceArtifactStore
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** Project roots are intentionally URI identities: moving a conversation never moves files. */
@Composable
internal fun ProjectsScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val projects by remember(context) { ProjectRepository.projects(context) }.collectAsState(initial = emptyList())
    var showCreate by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var selectedRoot by remember { mutableStateOf<Uri?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<ProjectEntity?>(null) }
    val rootLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        selectedRoot = uri
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.projects_title),
        onBack = onBack,
        actions = { top.yukonga.miuix.kmp.basic.TextButton(text = stringResource(R.string.projects_new), onClick = { name = ""; selectedRoot = null; error = null; showCreate = true }) },
    ) {
        item(key = "projects-info") {
            Card(Modifier.padding(horizontal = 12.dp).padding(top = 8.dp, bottom = 12.dp)) {
                BasicComponent(
                    title = stringResource(R.string.projects_title),
                    summary = stringResource(R.string.projects_summary),
                )
            }
        }
        item(key = "projects-toggle") {
            SwitchPreference(
                title = stringResource(R.string.projects_show_archived),
                summary = stringResource(R.string.projects_show_archived_summary),
                checked = showArchived,
                onCheckedChange = { showArchived = it },
            )
        }
        projects.filter { showArchived || it.archivedAt == null }.forEach { project ->
            item(key = project.id) { ProjectRow(context, project, scope, onError = { error = it }, onDetails = { details = project }) }
        }
        if (projects.none { showArchived || it.archivedAt == null }) {
            item(key = "projects-empty") { Text(stringResource(R.string.projects_empty), modifier = Modifier.padding(24.dp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        }
    }

    if (showCreate) {
        WindowDialog(show = true, title = stringResource(R.string.projects_new), summary = stringResource(R.string.projects_new_summary), onDismissRequest = { if (!busy) showCreate = false }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = name, onValueChange = { name = it.take(120) }, label = stringResource(R.string.projects_name), singleLine = true, modifier = Modifier.fillMaxWidth())
                ArrowPreference(
                    title = selectedRoot?.let { stringResource(R.string.projects_root_selected, it.lastPathSegment ?: it.toString()) } ?: stringResource(R.string.projects_choose_root),
                    onClick = { rootLauncher.launch(null) },
                )
                error?.let { Text(it, color = MiuixTheme.colorScheme.error) }
                MiuixDialogActions(
                    confirmText = if (busy) stringResource(R.string.projects_saving) else stringResource(R.string.projects_create),
                    confirmEnabled = !busy && name.isNotBlank() && selectedRoot != null,
                    onCancel = { if (!busy) showCreate = false },
                    onConfirm = {
                        val root = selectedRoot ?: return@MiuixDialogActions
                        busy = true
                        scope.launch {
                            runCatching {
                                ProjectRepository.create(context, ProjectDraft(name.trim(), "app", root.toString(), root.lastPathSegment ?: root.toString(), "SAF"))
                            }.onSuccess { showCreate = false }.onFailure { error = when ((it as? ProjectException)?.code) { "ROOT_ALREADY_REGISTERED" -> context.getString(R.string.projects_root_exists); else -> context.getString(R.string.projects_save_failed) } }
                            busy = false
                        }
                    },
                )
            }
        }
    }

    details?.let { project ->
        ProjectDetailsDialog(
            context = context,
            project = project,
            scope = scope,
            onDismiss = { details = null },
            onError = { error = it },
        )
    }
}

@Composable
private fun ProjectRow(context: Context, project: ProjectEntity, scope: kotlinx.coroutines.CoroutineScope, onError: (String) -> Unit, onDetails: () -> Unit) {
    var confirmDelete by remember(project.id) { mutableStateOf(false) }
    Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp)) {
        BasicComponent(title = project.name, summary = project.rootDisplayName)
        ArrowPreference(title = stringResource(R.string.projects_details), onClick = onDetails)
        ArrowPreference(
            title = if (project.archivedAt == null) stringResource(R.string.projects_archive) else stringResource(R.string.projects_restore),
            onClick = { scope.launch { runCatching { ProjectRepository.setArchived(context, project.id, project.archivedAt == null) }.onFailure { onError(context.getString(R.string.projects_save_failed)) } } },
        )
        ArrowPreference(title = stringResource(R.string.projects_delete), onClick = { confirmDelete = true })
    }
    if (confirmDelete) {
        WindowDialog(show = true, title = stringResource(R.string.projects_delete), summary = stringResource(R.string.projects_delete_summary), onDismissRequest = { confirmDelete = false }) {
            MiuixDialogActions(confirmText = stringResource(R.string.projects_delete), destructive = true, onCancel = { confirmDelete = false }, onConfirm = {
                scope.launch { runCatching { ProjectRepository.delete(context, project.id) }.onFailure { onError(context.getString(R.string.projects_save_failed)) }; confirmDelete = false }
            })
        }
    }
}

@Composable
private fun ProjectDetailsDialog(
    context: Context,
    project: ProjectEntity,
    scope: kotlinx.coroutines.CoroutineScope,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
) {
    var conversationId by remember(project.id) { mutableStateOf("") }
    var meetingId by remember(project.id) { mutableStateOf("") }
    var bindings by remember(project.id) { mutableStateOf<List<io.github.mangi.eta.fork.projects.ProjectSubjectBinding>>(emptyList()) }
    var artifacts by remember(project.id) { mutableStateOf(emptyList<io.github.mangi.eta.ui.app.WorkspaceArtifact>()) }
    LaunchedEffect(project.id) {
        bindings = runCatching { ProjectRepository.bindingsForProject(context, project.id) }.getOrDefault(emptyList())
        artifacts = WorkspaceArtifactStore(context).list(200).filter { it.projectId == project.id }
    }
    WindowDialog(show = true, title = project.name, summary = project.rootIdentityKey, onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.projects_bind_summary), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            TextField(value = conversationId, onValueChange = { conversationId = it.take(256) }, label = stringResource(R.string.projects_conversation_id), singleLine = true, modifier = Modifier.fillMaxWidth())
            TextField(value = meetingId, onValueChange = { meetingId = it.take(256) }, label = stringResource(R.string.projects_meeting_id), singleLine = true, modifier = Modifier.fillMaxWidth())
            MiuixDialogActions(
                confirmText = stringResource(R.string.projects_bind),
                confirmEnabled = conversationId.isNotBlank() || meetingId.isNotBlank(),
                onCancel = onDismiss,
                onConfirm = {
                    scope.launch {
                        runCatching {
                            conversationId.trim().takeIf { it.isNotBlank() }?.let { ProjectRepository.bindSubject(context, project.id, ProjectSubjectType.CONVERSATION, it) }
                            meetingId.trim().takeIf { it.isNotBlank() }?.let { ProjectRepository.bindSubject(context, project.id, ProjectSubjectType.MEETING, it) }
                            bindings = ProjectRepository.bindingsForProject(context, project.id)
                        }.onFailure { onError(context.getString(R.string.projects_save_failed)) }
                    }
                },
            )
            if (bindings.isNotEmpty()) {
                Text(stringResource(R.string.projects_bound_subjects), style = MiuixTheme.textStyles.subtitle)
                bindings.forEach { binding ->
                    Text("${binding.subjectType}: ${binding.subjectId}", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
            Text(stringResource(R.string.projects_artifacts, artifacts.size), style = MiuixTheme.textStyles.subtitle)
            artifacts.take(5).forEach { artifact -> Text(artifact.label, color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
        }
    }
}
