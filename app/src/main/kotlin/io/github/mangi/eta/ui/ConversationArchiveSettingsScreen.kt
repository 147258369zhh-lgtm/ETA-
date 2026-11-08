package io.github.mangi.eta.ui

import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.fork.conversations.ConversationArchiveScheduler
import io.github.mangi.eta.fork.conversations.ConversationArchiveSettings
import io.github.mangi.eta.fork.conversations.ConversationExtensionRepository
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val archiveIdleDayPresets = listOf(7, 14, 30, 60, 90)

@Composable
internal fun ConversationArchiveSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val applicationContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val checkStartedMessage = stringResource(R.string.conversation_archive_check_started)
    val settingsFlow = remember(applicationContext) {
        ConversationExtensionRepository.snapshot(applicationContext)
    }
    val snapshot by settingsFlow.collectAsState(initial = null)
    val settings = snapshot?.archiveSettings ?: ConversationArchiveSettings()

    fun updateSettings(transform: (ConversationArchiveSettings) -> ConversationArchiveSettings) {
        scope.launch {
            val updated = ConversationExtensionRepository.updateArchiveSettings(applicationContext, transform)
            ConversationArchiveScheduler.sync(applicationContext, updated)
        }
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.conversation_archive_settings_title),
        onBack = onBack,
    ) {
        item(key = "archive_behavior") {
            SmallTitle(stringResource(R.string.conversation_archive_group_behavior))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.conversation_archive_enabled),
                    summary = stringResource(R.string.conversation_archive_enabled_summary),
                    checked = settings.enabled,
                    onCheckedChange = { enabled ->
                        updateSettings { it.copy(enabled = enabled) }
                    },
                )
                OverlayDropdownPreference(
                    title = stringResource(R.string.conversation_archive_idle_days),
                    items = archiveIdleDayPresets.map { days ->
                        stringResource(R.string.conversation_archive_days_value, days)
                    },
                    selectedIndex = archiveIdleDayPresets.indexOf(settings.idleDays)
                        .takeIf { it >= 0 }
                        ?: archiveIdleDayPresets.indexOf(30),
                    onSelectedIndexChange = { index ->
                        archiveIdleDayPresets.getOrNull(index)?.let { days ->
                            updateSettings { it.copy(idleDays = days) }
                        }
                    },
                )
            }
        }

        item(key = "archive_exemptions") {
            SmallTitle(stringResource(R.string.conversation_archive_group_exemptions))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.conversation_archive_exempt_pinned),
                    checked = settings.exemptPinned,
                    onCheckedChange = { exempt ->
                        updateSettings { it.copy(exemptPinned = exempt) }
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.conversation_archive_exempt_meetings),
                    checked = settings.exemptMeetings,
                    onCheckedChange = { exempt ->
                        updateSettings { it.copy(exemptMeetings = exempt) }
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.conversation_archive_exempt_running),
                    checked = settings.exemptRunning,
                    onCheckedChange = { exempt ->
                        updateSettings { it.copy(exemptRunning = exempt) }
                    },
                )
            }
        }

        item(key = "archive_check") {
            SmallTitle(stringResource(R.string.conversation_archive_group_check))
            Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                BasicComponent(
                    title = stringResource(R.string.conversation_archive_check_now),
                    summary = settings.lastError?.let { error ->
                        stringResource(R.string.conversation_archive_last_error, error)
                    } ?: settings.lastCheckedAt?.let { checkedAt ->
                        stringResource(
                            R.string.conversation_archive_last_checked,
                            DateUtils.getRelativeTimeSpanString(
                                checkedAt,
                                System.currentTimeMillis(),
                                DateUtils.MINUTE_IN_MILLIS,
                            ),
                        )
                    } ?: stringResource(R.string.conversation_archive_never_checked),
                    summaryColor = if (settings.lastError != null) {
                        BasicComponentDefaults.summaryColor(color = MiuixTheme.colorScheme.error)
                    } else {
                        BasicComponentDefaults.summaryColor(
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    },
                    enabled = settings.enabled,
                    onClick = {
                        ConversationArchiveScheduler.checkNow(applicationContext)
                        Toast.makeText(
                            context,
                            checkStartedMessage,
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
            }
        }
    }
}
