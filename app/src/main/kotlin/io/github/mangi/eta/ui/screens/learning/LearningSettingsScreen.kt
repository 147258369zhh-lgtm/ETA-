package io.github.mangi.eta.ui.screens.learning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import io.github.mangi.eta.fork.learning.LearningCandidateView
import io.github.mangi.eta.fork.learning.LearningMutationResult
import io.github.mangi.eta.fork.learning.LearningRepository
import io.github.mangi.eta.fork.learning.LearningRuleView
import io.github.mangi.eta.fork.learning.LearningSettingsStore
import io.github.mangi.eta.fork.learning.LearningSnapshot
import io.github.mangi.eta.fork.learning.LearningStatus
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.StatusSuccess
import io.github.mangi.eta.ui.components.StatusWarning
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

/** User-governed self-learning: capture is automatic, promotion and rollback stay explicit. */
@Composable
internal fun LearningSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(context.applicationContext) { LearningRepository.get(context.applicationContext) }
    var snapshot by remember { mutableStateOf(LearningSnapshot(emptyList(), emptyList())) }
    var enabled by remember { mutableStateOf(LearningSettingsStore.isEnabled(context)) }
    var autoCapture by remember { mutableStateOf(LearningSettingsStore.automaticCaptureEnabled(context)) }
    var inspectedCandidate by remember { mutableStateOf<LearningCandidateView?>(null) }
    var inspectedRule by remember { mutableStateOf<LearningRuleView?>(null) }
    var mutationError by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        snapshot = withContext(Dispatchers.IO) { repository.snapshot() }
    }
    LaunchedEffect(Unit) { reload() }

    fun mutate(block: suspend () -> LearningMutationResult) {
        scope.launch {
            mutationError = null
            when (val result = withContext(Dispatchers.IO) { block() }) {
                is LearningMutationResult.Success -> {
                    snapshot = result.snapshot
                    inspectedCandidate = result.snapshot.candidates.firstOrNull {
                        it.id == inspectedCandidate?.id
                    }
                    inspectedRule = result.snapshot.rules.firstOrNull { it.id == inspectedRule?.id }
                }
                is LearningMutationResult.Rejected -> mutationError = result.reason
            }
        }
    }

    MiuixScaffoldPage(
        title = "自学习",
        onBack = onBack,
        overlay = {
            val candidate = inspectedCandidate
            WindowDialog(
                show = candidate != null,
                title = candidate?.title.orEmpty(),
                onDismissRequest = { inspectedCandidate = null },
            ) {
                if (candidate != null) {
                    CandidateDetail(
                        candidate = candidate,
                        error = mutationError,
                        onConfirm = {
                            mutate {
                                repository.confirm(
                                    candidate.id,
                                    overrideConflict = candidate.status == LearningStatus.CONFLICT,
                                )
                            }
                        },
                        onPromote = { mutate { repository.promote(candidate.id) } },
                        onReject = { mutate { repository.reject(candidate.id) } },
                        onClose = { inspectedCandidate = null },
                    )
                }
            }
            val rule = inspectedRule
            WindowDialog(
                show = rule != null,
                title = rule?.title.orEmpty(),
                onDismissRequest = { inspectedRule = null },
            ) {
                if (rule != null) {
                    RuleVersions(
                        rule = rule,
                        onRollback = { versionId ->
                            mutate { repository.rollback(rule.id, versionId) }
                        },
                        onArchive = { mutate { repository.archiveRule(rule.id) } },
                    )
                }
            }
        },
    ) {
        item(key = "switches") {
            SmallTitle("学习策略")
            Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                SwitchPreference(
                    title = "启用自学习",
                    summary = "允许 Agent 检索已确认规则并提交候选经验",
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        LearningSettingsStore.setEnabled(context, it)
                        if (!it) {
                            autoCapture = false
                            LearningSettingsStore.setAutomaticCaptureEnabled(context, false)
                        }
                    },
                )
                SwitchPreference(
                    title = "自动捕获候选",
                    summary = "失败与可复用经验会先脱敏、去重并进入待审批区",
                    checked = enabled && autoCapture,
                    enabled = enabled,
                    onCheckedChange = {
                        autoCapture = it
                        LearningSettingsStore.setAutomaticCaptureEnabled(context, it)
                    },
                )
                Text(
                    text = "自动晋升保持关闭。候选必须由你确认，之后才能写入长期规则；所有版本都可回滚。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }

        val pending = snapshot.candidates.filter {
            it.status in setOf(LearningStatus.PENDING, LearningStatus.CONFLICT, LearningStatus.CONFIRMED)
        }
        item(key = "pending_title") { SmallTitle("待审批候选") }
        if (pending.isEmpty()) {
            item(key = "pending_empty") {
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ListEmptyState(
                        title = "没有待审批经验",
                        summary = "Agent 提交的经验会先显示在这里，不会直接改变长期记忆。",
                        icon = Icons.Rounded.Psychology,
                    )
                }
            }
        } else {
            pending.forEach { candidate ->
                item(key = "candidate:${candidate.id}") {
                    CandidateCard(candidate) { inspectedCandidate = candidate }
                }
            }
        }
        mutationError?.takeIf { inspectedCandidate == null && inspectedRule == null }?.let { error ->
            item(key = "mutation_error") {
                Text(
                    text = error,
                    color = StatusError,
                    style = MiuixTheme.textStyles.footnote1,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }

        item(key = "rules_title") { SmallTitle("已晋升规则") }
        if (snapshot.rules.isEmpty()) {
            item(key = "rules_empty") {
                Text(
                    text = "尚未确认任何长期规则。",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        } else {
            snapshot.rules.forEach { rule ->
                item(key = "rule:${rule.id}") {
                    RuleCard(rule) { inspectedRule = rule }
                }
            }
        }
    }
}

@Composable
private fun CandidateCard(candidate: LearningCandidateView, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = candidate.title,
                    style = MiuixTheme.textStyles.body1,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${candidate.status.wireValue} · ${(candidate.confidence * 100).toInt()}% · ${candidate.kind.wireValue}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = when (candidate.status) {
                        LearningStatus.CONFLICT -> StatusWarning
                        LearningStatus.CONFIRMED -> StatusSuccess
                        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                    },
                )
                Text(
                    text = candidate.content,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            TextButton(text = "审阅", onClick = onClick)
        }
    }
}

@Composable
private fun CandidateDetail(
    candidate: LearningCandidateView,
    error: String?,
    onConfirm: () -> Unit,
    onPromote: () -> Unit,
    onReject: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(candidate.content, style = MiuixTheme.textStyles.body1)
        Text(
            text = "类型：${candidate.kind.wireValue} · 置信度 ${(candidate.confidence * 100).toInt()}% · 来源 ${candidate.sourceRef}",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        if (candidate.evidence.isNotEmpty()) {
            SmallTitle("证据")
            candidate.evidence.forEach { evidence ->
                Text(
                    text = evidence.excerpt,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        error?.let { Text(it, color = StatusError, style = MiuixTheme.textStyles.footnote1) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (candidate.status in setOf(LearningStatus.PENDING, LearningStatus.CONFLICT)) {
                TextButton(text = "拒绝", onClick = onReject, modifier = Modifier.weight(1f))
                TextButton(
                    text = if (candidate.status == LearningStatus.CONFLICT) "覆盖冲突并确认" else "确认",
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                )
            } else if (candidate.status == LearningStatus.CONFIRMED) {
                TextButton(
                    text = "晋升为长期规则",
                    onClick = onPromote,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        MiuixDialogActions(confirmText = "关闭", onCancel = onClose, onConfirm = onClose)
    }
}

@Composable
private fun RuleCard(rule: LearningRuleView, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.title, style = MiuixTheme.textStyles.body1, fontWeight = FontWeight.Medium)
                Text(
                    text = "v${rule.currentVersion} · ${if (rule.active) "生效" else "已归档"}",
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (rule.active) StatusSuccess else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    rule.content,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            TextButton(text = "版本", onClick = onClick)
        }
    }
}

@Composable
private fun RuleVersions(
    rule: LearningRuleView,
    onRollback: (String) -> Unit,
    onArchive: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(rule.content, style = MiuixTheme.textStyles.body1)
        rule.versions.sortedByDescending { it.version }.forEach { version ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "v${version.version} · ${version.reason}",
                        style = MiuixTheme.textStyles.footnote1,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        version.content,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (version.version != rule.currentVersion) {
                    TextButton(text = "回滚", onClick = { onRollback(version.id) })
                }
            }
        }
        if (rule.active) {
            TextButton(text = "归档此规则", onClick = onArchive, modifier = Modifier.fillMaxWidth())
        }
    }
}
