package io.github.mangi.eta.ui.screens.backup

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.core.safeStackTrace
import io.github.mangi.eta.data.repository.EtaBackupSummary
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun DataBackupScreen(
    context: Context,
    onBack: () -> Unit,
    onExport: suspend (OutputStream, String?) -> EtaBackupSummary,
    onImport: suspend (InputStream, String?) -> EtaBackupSummary,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }

    fun showFailure(throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
        AndroidAgentLogger.error("Agent backup UI failed: type=${throwable.safeLogType()}\n${throwable.safeStackTrace()}")
        Toast.makeText(
            context,
            throwable.message ?: context.getString(R.string.data_backup_failed),
            Toast.LENGTH_LONG,
        ).show()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error(context.getString(R.string.data_backup_file_open_failed))
                val summary = output.use { onExport(it, exportPassword.takeIf(String::isNotBlank)) }
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.data_backup_exported,
                        summary.conversationCount,
                        summary.providerCount,
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (throwable: Throwable) {
                showFailure(throwable)
            } finally {
                busy = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            importPassword = ""
            showImportDialog = true
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.data_backup_title),
        onBack = onBack,
    ) {
        item(key = "warning") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(top = 8.dp, bottom = 12.dp),
            ) {
                BasicComponent(
                    title = stringResource(R.string.data_backup_warning_title),
                    summary = stringResource(R.string.data_backup_warning_summary),
                )
            }
        }
        item(key = "actions") {
            SmallTitle(stringResource(R.string.data_backup_actions))
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                ArrowPreference(
                    title = stringResource(R.string.data_backup_export),
                    summary = if (busy) {
                        stringResource(R.string.data_backup_working)
                    } else {
                        stringResource(R.string.data_backup_export_summary)
                    },
                    enabled = !busy,
                    endActions = {
                        if (busy) {
                            InfiniteProgressIndicator(size = 20.dp)
                        }
                    },
                    onClick = {
                        exportPassword = ""
                        showExportDialog = true
                    },
                )
                ArrowPreference(
                    title = stringResource(R.string.data_backup_import),
                    summary = stringResource(R.string.data_backup_import_summary),
                    enabled = !busy,
                    onClick = {
                        importLauncher.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "text/plain"))
                    },
                )
            }
        }
    }

    if (showImportDialog) {
        WindowDialog(
            show = true,
            title = stringResource(R.string.data_backup_import_confirm_title),
            summary = stringResource(R.string.data_backup_import_confirm_summary),
            onDismissRequest = {
                if (!busy) {
                    showImportDialog = false
                    pendingImportUri = null
                    importPassword = ""
                }
            },
        ) {
            TextField(
                value = importPassword,
                onValueChange = { importPassword = it },
                label = stringResource(R.string.data_backup_password_optional),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            MiuixDialogActions(
                confirmText = if (busy) {
                    stringResource(R.string.data_backup_working)
                } else {
                    stringResource(R.string.action_import)
                },
                destructive = true,
                cancelEnabled = !busy,
                confirmEnabled = !busy,
                onCancel = {
                    showImportDialog = false
                    pendingImportUri = null
                    importPassword = ""
                },
                onConfirm = {
                    val uri = pendingImportUri ?: return@MiuixDialogActions
                    showImportDialog = false
                    scope.launch {
                        busy = true
                        try {
                            val input = context.contentResolver.openInputStream(uri)
                                ?: error(context.getString(R.string.data_backup_file_open_failed))
                            val summary = input.use {
                                onImport(it, importPassword.takeIf(String::isNotBlank))
                            }
                            Toast.makeText(
                                context,
                                if (summary.restartRequired) {
                                    context.getString(
                                        R.string.data_backup_staged_restart,
                                        summary.conversationCount,
                                        summary.providerCount,
                                    )
                                } else {
                                    context.getString(
                                        R.string.data_backup_imported,
                                        summary.conversationCount,
                                        summary.providerCount,
                                    )
                                },
                                Toast.LENGTH_SHORT,
                            ).show()
                        } catch (throwable: Throwable) {
                            showFailure(throwable)
                        } finally {
                            pendingImportUri = null
                            importPassword = ""
                            busy = false
                        }
                    }
                },
            )
        }
    }

    if (showExportDialog) {
        WindowDialog(
            show = true,
            title = stringResource(R.string.data_backup_encrypt_title),
            summary = stringResource(R.string.data_backup_encrypt_summary),
            onDismissRequest = { if (!busy) showExportDialog = false },
        ) {
            TextField(
                value = exportPassword,
                onValueChange = { exportPassword = it },
                label = stringResource(R.string.data_backup_password_optional),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            MiuixDialogActions(
                confirmText = stringResource(R.string.data_backup_choose_location),
                cancelEnabled = !busy,
                confirmEnabled = !busy,
                onCancel = { showExportDialog = false },
                onConfirm = {
                    showExportDialog = false
                    exportLauncher.launch(defaultBackupFileName())
                },
            )
        }
    }
}

private fun defaultBackupFileName(): String =
    "Eta-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.eta-backup"
