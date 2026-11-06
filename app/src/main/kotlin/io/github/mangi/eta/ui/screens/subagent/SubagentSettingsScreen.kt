package io.github.mangi.eta.ui.screens.subagent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.subagent.SubagentProfile
import io.github.mangi.eta.agent.subagent.SubagentProfileCatalog
import io.github.mangi.eta.agent.subagent.SubagentPermissionCategory
import io.github.mangi.eta.agent.subagent.SubagentPermissionCatalog
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.SubagentProfileBinding
import io.github.mangi.eta.data.model.SubagentSettings
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.components.EtaChoiceChip
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.ModelBrandMark
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.model.filterGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private data class CustomSubagentDraft(
    val originalName: String? = null,
    val name: String = "",
    val description: String = "",
    val toolsText: String = "",
    val prompt: String = "",
)

private fun SubagentSettings.withProfileBinding(
    profileName: String,
    binding: SubagentProfileBinding?,
): SubagentSettings {
    val key = profileName.trim().lowercase()
    val normalized = binding?.copy(
        providerId = binding.providerId?.trim()?.takeIf(String::isNotEmpty),
        modelId = binding.modelId?.trim()?.takeIf(String::isNotEmpty),
        reasoningEffort = binding.reasoningEffort?.trim()?.takeIf(String::isNotEmpty),
        tools = binding.tools
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.distinct(),
        disallowedTools = binding.disallowedTools
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct(),
        contextWindow = binding.contextWindow?.takeIf { it > 0 },
        maxToolCalls = binding.maxToolCalls?.takeIf { it > 0 },
    )?.takeUnless {
        it.providerId == null &&
            it.modelId == null &&
            it.reasoningEffort == null &&
            it.tools == null &&
            it.disallowedTools.isEmpty() &&
            it.contextWindow == null &&
            it.maxToolCalls == null
    }
    val updated = profiles.toMutableMap()
    if (normalized == null) updated.remove(key) else updated[key] = normalized
    return copy(profiles = updated)
}

private fun SubagentSettings.renamedProfileBinding(
    oldName: String,
    newName: String,
): SubagentSettings {
    val oldKey = oldName.trim().lowercase()
    val binding = profiles[oldKey] ?: return this
    val updated = profiles.toMutableMap()
    updated.remove(oldKey)
    updated[newName.trim().lowercase()] = binding
    return copy(profiles = updated)
}

private fun SubagentProfileBinding?.orEmpty(): SubagentProfileBinding =
    this ?: SubagentProfileBinding()

private val profileContextBudgetOptions = listOf<Int?>(
    null,
    16_000,
    32_000,
    64_000,
    128_000,
    256_000,
    512_000,
    1_000_000,
)

private val profileToolCallBudgetOptions = listOf<Int?>(null, 8, 16, 32, 64)

private fun tokenBudgetLabel(value: Int): String = when {
    value >= 1_000_000 -> "${value / 1_000_000}M"
    value >= 1_000 -> "${value / 1_000}K"
    else -> value.toString()
}

@Composable
internal fun SubagentSettingsScreen(
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val agentPrefs = remember { Prefs.localAgentPreferences() }
    val defaultEnabled = Prefs.Keys.BOOLEAN_DEFAULTS.getValue(Prefs.Keys.AGENT_SUBAGENT_ENABLED)
    var subagentEnabled by remember(agentPrefs) {
        mutableStateOf(agentPrefs?.getBoolean(Prefs.Keys.AGENT_SUBAGENT_ENABLED, defaultEnabled) ?: defaultEnabled)
    }

    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val primaryProviderId by RuntimeConfigRepository.selectedProviderIdFlow().collectAsState(initial = null)
    val primaryModelId by RuntimeConfigRepository.selectedModelIdFlow().collectAsState(initial = null)
    val subagentSettings by RuntimeConfigRepository.subagentSettingsFlow().collectAsState(initial = SubagentSettings())

    var customProfiles by remember { mutableStateOf<List<SubagentProfile>>(emptyList()) }
    fun reloadCustomProfiles() {
        scope.launch {
            customProfiles = withContext(Dispatchers.IO) {
                SubagentProfileCatalog.listCustom()
            }
        }
    }
    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
        reloadCustomProfiles()
    }

    val pickerState = remember(providers, primaryProviderId, primaryModelId) {
        AgentModelPickerProjector.project(providers, primaryProviderId, primaryModelId)
    }
    val resolvedSecondary = remember(providers, subagentSettings.modelId) {
        subagentSettings.modelId?.takeIf { it.isNotBlank() }?.let { id ->
            providers.enabledModel(id)
        }
    }
    val primaryModelName = pickerState.selectedModel?.displayName
    val secondarySummary = when {
        resolvedSecondary != null -> "${resolvedSecondary.provider.name} / ${resolvedSecondary.model.displayName}"
        !primaryModelName.isNullOrBlank() -> "跟随主模型 ($primaryModelName)"
        else -> "跟随主模型"
    }

    val effectiveCapabilities = remember(providers, resolvedSecondary, primaryProviderId, primaryModelId) {
        val target = resolvedSecondary ?: providers.enabledModel(primaryModelId)
        target?.let { (provider, model) ->
            RuntimeConfigRepository.reasoningCapabilities(provider, model)
        }
    }
    val effortOptions = remember(effectiveCapabilities) {
        val efforts = effectiveCapabilities?.selectableEfforts.orEmpty()
        listOf<ReasoningEffort?>(null) + efforts
    }
    val effortLabels = remember(effortOptions) {
        effortOptions.map { effort ->
            when (effort) {
                null -> "跟随模型默认"
                ReasoningEffort.DEFAULT -> "Auto"
                else -> effort.displayName
            }
        }
    }
    val selectedEffortIndex = remember(effortOptions, subagentSettings.reasoningEffort) {
        val current = subagentSettings.resolvedReasoningEffort
        effortOptions.indexOf(current).takeIf { it >= 0 } ?: 0
    }

    var showModelSheet by remember { mutableStateOf(false) }
    var modelSheetProfileName by remember { mutableStateOf<String?>(null) }
    var toolPolicyProfileName by remember { mutableStateOf<String?>(null) }
    var inspectedProfile by remember { mutableStateOf<SubagentProfile?>(null) }
    var editorDraft by remember { mutableStateOf<CustomSubagentDraft?>(null) }
    var editorError by remember { mutableStateOf<String?>(null) }

    MiuixScaffoldPage(
        title = "Subagent",
        onBack = onBack,
        actions = {
            IconButton(
                onClick = {
                    editorError = null
                    editorDraft = CustomSubagentDraft()
                },
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "新建 Subagent",
                )
            }
        },
        overlay = {
            OverlayBottomSheet(
                show = showModelSheet,
                title = modelSheetProfileName?.let { "$it 模型" } ?: "Subagent 默认模型",
                onDismissRequest = { showModelSheet = false },
                insideMargin = DpSize(0.dp, 0.dp),
            ) {
                val targetProfileName = modelSheetProfileName
                val targetBinding = targetProfileName?.let(subagentSettings::bindingFor)
                SubagentModelPickerContent(
                    pickerState = pickerState,
                    selectedProviderId = if (targetProfileName == null) {
                        resolvedSecondary?.provider?.id
                    } else {
                        targetBinding?.providerId
                    },
                    selectedSecondaryModelId = if (targetProfileName == null) {
                        subagentSettings.modelId
                    } else {
                        targetBinding?.modelId
                    },
                    primaryModelName = primaryModelName,
                    onSelectModel = { providerId, modelId ->
                        showModelSheet = false
                        scope.launch {
                            val updated = if (targetProfileName == null) {
                                subagentSettings.copy(modelId = modelId)
                            } else {
                                subagentSettings.withProfileBinding(
                                    targetProfileName,
                                    targetBinding.orEmpty().copy(
                                        providerId = providerId,
                                        modelId = modelId,
                                    ),
                                )
                            }
                            RuntimeConfigRepository.setSubagentSettings(updated)
                        }
                    },
                )
            }

            val policyProfileName = toolPolicyProfileName
            val policyBinding = policyProfileName?.let(subagentSettings::bindingFor)
            val policyProfile = policyProfileName?.let(SubagentProfileCatalog::get)
            var selectedPermissionTools by remember(
                policyProfileName,
                policyBinding?.tools,
                policyProfile?.explicitTools,
            ) {
                val configured = policyBinding?.tools
                mutableStateOf(
                    when {
                        configured == null -> policyProfile?.let(SubagentPermissionCatalog::defaultTools).orEmpty()
                        "*" in configured -> SubagentPermissionCatalog.allTools
                        else -> configured.toSet()
                    },
                )
            }
            WindowDialog(
                show = policyProfileName != null,
                title = policyProfileName?.let { "$it 权限" } ?: "Subagent 权限",
                onDismissRequest = { toolPolicyProfileName = null },
            ) {
                if (policyProfileName != null) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = "只选择这个 Agent 真正需要的权限。分类可整体切换，展开后可逐项核对。",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        SubagentPermissionEditor(
                            selectedTools = selectedPermissionTools,
                            onSelectedToolsChange = { selectedPermissionTools = it },
                            maxHeight = 430.dp,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        MiuixDialogActions(
                            confirmText = "保存",
                            onCancel = { toolPolicyProfileName = null },
                            onConfirm = {
                                scope.launch {
                                    RuntimeConfigRepository.setSubagentSettings(
                                        subagentSettings.withProfileBinding(
                                            policyProfileName,
                                            policyBinding.orEmpty().copy(
                                                tools = SubagentPermissionCatalog.normalizeSavedTools(
                                                    selectedPermissionTools,
                                                ),
                                            ),
                                        ),
                                    )
                                    toolPolicyProfileName = null
                                }
                            },
                        )
                    }
                }
            }

            val currentProfile = inspectedProfile
            OverlayBottomSheet(
                show = currentProfile != null,
                title = currentProfile?.name.orEmpty(),
                onDismissRequest = { inspectedProfile = null },
            ) {
                if (currentProfile != null) {
                    val profileBinding = subagentSettings.bindingFor(currentProfile.name)
                    val profileTarget = profileBinding?.modelId?.let { id ->
                        providers.enabledModel(id)?.takeIf { target ->
                            profileBinding.providerId.isNullOrBlank() ||
                                target.provider.id == profileBinding.providerId
                        }
                    }
                    val globalTarget = subagentSettings.modelId?.let(providers::enabledModel)
                    val primaryTarget = primaryModelId?.let(providers::enabledModel)
                    val effectiveTarget = profileTarget ?: globalTarget ?: primaryTarget
                    val profileCapabilities = effectiveTarget?.let { target ->
                        RuntimeConfigRepository.reasoningCapabilities(target.provider, target.model)
                    }
                    val profileEffortOptions = listOf<ReasoningEffort?>(null) +
                        profileCapabilities?.selectableEfforts.orEmpty()
                    val globalEffortLabel = subagentSettings.resolvedReasoningEffort?.displayName
                    val profileEffortLabels = profileEffortOptions.map { effort ->
                        when (effort) {
                            null -> globalEffortLabel?.let { "跟随全局（$it）" } ?: "跟随全局"
                            ReasoningEffort.DEFAULT -> "Auto"
                            else -> effort.displayName
                        }
                    }
                    val profileEffort = profileBinding?.resolvedReasoningEffort
                    val profileEffortIndex = profileEffortOptions.indexOf(profileEffort)
                        .takeIf { it >= 0 } ?: 0
                    val profileModelSummary = when {
                        profileTarget != null ->
                            "${profileTarget.provider.name} / ${profileTarget.model.displayName}"
                        !profileBinding?.modelId.isNullOrBlank() ->
                            "绑定模型已失效，将回退到全局或主模型"
                        else -> "跟随全局或主模型"
                    }
                    val contextBudgetIndex = profileContextBudgetOptions
                        .indexOf(profileBinding?.contextWindow)
                        .takeIf { it >= 0 }
                        ?: 0
                    val toolCallBudgetIndex = profileToolCallBudgetOptions
                        .indexOf(profileBinding?.maxToolCalls)
                        .takeIf { it >= 0 }
                        ?: 0
                    val profileTools = profileBinding?.tools
                    val toolPolicySummary = when {
                        profileTools == null -> "跟随 Profile Markdown"
                        profileTools == listOf("*") -> "全部非 GUI 工具"
                        profileTools.isEmpty() -> "不允许任何工具"
                        else -> {
                            val selected = profileTools.toSet()
                            val labels = SubagentPermissionCatalog.categories.filter { category ->
                                category.tools.isNotEmpty() && selected.containsAll(category.tools)
                            }.map { it.title }
                            labels.takeIf(List<String>::isNotEmpty)?.joinToString(" · ")
                                ?: "自定义 ${profileTools.size} 项工具"
                        }
                    } + profileBinding?.disallowedTools
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { " · 禁用 ${it.joinToString(", ").take(60)}" }
                        .orEmpty()
                    SubagentProfileDetailContent(
                        profile = currentProfile,
                        modelSummary = profileModelSummary,
                        effortOptions = profileEffortOptions,
                        effortLabels = profileEffortLabels,
                        selectedEffortIndex = profileEffortIndex,
                        toolPolicySummary = toolPolicySummary,
                        contextBudgetOptions = profileContextBudgetOptions,
                        contextBudgetLabels = profileContextBudgetOptions.map { value ->
                            value?.let { "${tokenBudgetLabel(it)} tokens" } ?: "跟随模型窗口"
                        },
                        selectedContextBudgetIndex = contextBudgetIndex,
                        toolCallBudgetOptions = profileToolCallBudgetOptions,
                        toolCallBudgetLabels = profileToolCallBudgetOptions.map { value ->
                            value?.let { "$it 次" } ?: "不设 Profile 上限"
                        },
                        selectedToolCallBudgetIndex = toolCallBudgetIndex,
                        onConfigureModel = {
                            inspectedProfile = null
                            modelSheetProfileName = currentProfile.name
                            showModelSheet = true
                        },
                        onConfigureTools = { toolPolicyProfileName = currentProfile.name },
                        onSelectEffort = { effort ->
                            scope.launch {
                                RuntimeConfigRepository.setSubagentSettings(
                                    subagentSettings.withProfileBinding(
                                        currentProfile.name,
                                        profileBinding.orEmpty().copy(
                                            reasoningEffort = effort?.wireValue,
                                        ),
                                    ),
                                )
                            }
                        },
                        onSelectContextBudget = { value ->
                            scope.launch {
                                RuntimeConfigRepository.setSubagentSettings(
                                    subagentSettings.withProfileBinding(
                                        currentProfile.name,
                                        profileBinding.orEmpty().copy(contextWindow = value),
                                    ),
                                )
                            }
                        },
                        onSelectToolCallBudget = { value ->
                            scope.launch {
                                RuntimeConfigRepository.setSubagentSettings(
                                    subagentSettings.withProfileBinding(
                                        currentProfile.name,
                                        profileBinding.orEmpty().copy(maxToolCalls = value),
                                    ),
                                )
                            }
                        },
                        onEdit = if (!currentProfile.builtin) {
                            {
                                val toolsText = SubagentPermissionCatalog.defaultTools(currentProfile)
                                    .sorted()
                                    .joinToString(", ")
                                inspectedProfile = null
                                editorError = null
                                editorDraft = CustomSubagentDraft(
                                    originalName = currentProfile.name,
                                    name = currentProfile.name,
                                    description = currentProfile.summary,
                                    toolsText = toolsText,
                                    prompt = currentProfile.roleInstruction,
                                )
                            }
                        } else {
                            null
                        },
                        onDelete = if (!currentProfile.builtin) {
                            {
                                val targetName = currentProfile.name
                                inspectedProfile = null
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        SubagentProfileCatalog.deleteCustomProfile(targetName)
                                    }
                                    RuntimeConfigRepository.setSubagentSettings(
                                        subagentSettings.copy(
                                            profiles = subagentSettings.profiles - targetName.lowercase(),
                                        ),
                                    )
                                    reloadCustomProfiles()
                                }
                            }
                        } else {
                            null
                        },
                    )
                }
            }

            val activeDraft = editorDraft
            WindowDialog(
                show = activeDraft != null,
                title = if (activeDraft?.originalName != null) "编辑 Subagent" else "新建 Subagent",
                onDismissRequest = { editorDraft = null },
            ) {
                if (activeDraft != null) {
                    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp)) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            TextField(
                                value = activeDraft.name,
                                onValueChange = { editorDraft = activeDraft.copy(name = it) },
                                label = "名称 (kebab-case，如 bill-analyzer)",
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            TextField(
                                value = activeDraft.description,
                                onValueChange = { editorDraft = activeDraft.copy(description = it) },
                                label = "简介 (Description)",
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = "权限",
                                style = MiuixTheme.textStyles.body1,
                                fontWeight = FontWeight.SemiBold,
                            )
                            SubagentPermissionEditor(
                                selectedTools = activeDraft.toolsText
                                    .split(",")
                                    .map(String::trim)
                                    .filter(String::isNotEmpty)
                                    .toSet(),
                                onSelectedToolsChange = { tools ->
                                    editorDraft = activeDraft.copy(
                                        toolsText = tools.sorted().joinToString(", "),
                                    )
                                },
                                maxHeight = 360.dp,
                            )
                            TextField(
                                value = activeDraft.prompt,
                                onValueChange = { editorDraft = activeDraft.copy(prompt = it) },
                                label = "角色指令 (System Prompt)",
                                minLines = 4,
                                maxLines = 8,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            editorError?.let { err ->
                                Text(
                                    text = err,
                                    style = MiuixTheme.textStyles.footnote1,
                                    color = StatusError,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        MiuixDialogActions(
                            confirmText = "保存",
                            onCancel = { editorDraft = null },
                            onConfirm = {
                                scope.launch {
                                    val tools = activeDraft.toolsText
                                        .split(",")
                                        .map { it.trim() }
                                        .filter { it.isNotEmpty() }
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val saved = SubagentProfileCatalog.saveCustomProfile(
                                                name = activeDraft.name,
                                                description = activeDraft.description,
                                                tools = tools,
                                                prompt = activeDraft.prompt,
                                            )
                                            val oldName = activeDraft.originalName
                                            if (oldName != null && oldName != saved.name) {
                                                SubagentProfileCatalog.deleteCustomProfile(oldName)
                                            }
                                            saved to oldName
                                        }
                                    }
                                    result.fold(
                                        onSuccess = { (saved, oldName) ->
                                            if (oldName != null && oldName != saved.name) {
                                                RuntimeConfigRepository.setSubagentSettings(
                                                    subagentSettings.renamedProfileBinding(oldName, saved.name),
                                                )
                                            }
                                            editorDraft = null
                                            editorError = null
                                            reloadCustomProfiles()
                                        },
                                        onFailure = { error ->
                                            editorError = error.message ?: "保存失败"
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        },
    ) {
        item(key = "config_group") {
            SmallTitle("通用与模型")
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                SwitchPreference(
                    title = "启用 Subagent",
                    checked = subagentEnabled,
                    enabled = agentPrefs != null,
                    onCheckedChange = { checked ->
                        val targetPrefs = agentPrefs ?: return@SwitchPreference
                        if (targetPrefs.edit().putBoolean(Prefs.Keys.AGENT_SUBAGENT_ENABLED, checked).commit()) {
                            subagentEnabled = checked
                            Prefs.reconcileAgentPreferences(EtaApp.serviceInstance)
                            scope.launch {
                                RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
                            }
                        }
                    },
                )
                ArrowPreference(
                    title = "Subagent 默认模型",
                    summary = secondarySummary,
                    enabled = subagentEnabled,
                    onClick = {
                        modelSheetProfileName = null
                        showModelSheet = true
                    },
                )
                if (effortOptions.size > 1) {
                    OverlayDropdownPreference(
                        title = "思考强度",
                        items = effortLabels,
                        selectedIndex = selectedEffortIndex,
                        enabled = subagentEnabled,
                        onSelectedIndexChange = { index ->
                            val chosen = effortOptions.getOrNull(index)
                            scope.launch {
                                RuntimeConfigRepository.setSubagentSettings(
                                    subagentSettings.copy(reasoningEffort = chosen?.wireValue),
                                )
                            }
                        },
                    )
                }
            }
        }

        item(key = "builtin_group") {
            SmallTitle("Built-in Subagents")
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                SubagentProfile.BUILTIN_PROFILES.forEach { profile ->
                    ArrowPreference(
                        title = profile.name,
                        summary = profile.summary,
                        onClick = { inspectedProfile = profile },
                    )
                }
            }
        }

        item(key = "custom_group") {
            SmallTitle("Custom Subagents")
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                if (customProfiles.isEmpty()) {
                    ListEmptyState(
                        title = "暂无自定义 Subagent",
                        summary = "可在对话中直接让 Eta 创建专属 Subagent（保存在 agents/<name>.md），或点击右上角 + 手动新建",
                        icon = Icons.Rounded.Extension,
                        action = {
                            TextButton(
                                text = "新建 Subagent",
                                onClick = {
                                    editorError = null
                                    editorDraft = CustomSubagentDraft()
                                },
                            )
                        },
                    )
                } else {
                    customProfiles.forEach { profile ->
                        ArrowPreference(
                            title = profile.name,
                            summary = profile.summary,
                            onClick = { inspectedProfile = profile },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubagentPermissionEditor(
    selectedTools: Set<String>,
    onSelectedToolsChange: (Set<String>) -> Unit,
    maxHeight: androidx.compose.ui.unit.Dp,
) {
    var expandedCategory by remember { mutableStateOf<String?>(null) }
    val selectedCategories = SubagentPermissionCatalog.selectedCategoryIds(selectedTools)
    val partialCategories = SubagentPermissionCatalog.partialCategoryIds(selectedTools)
    val readOnlyTools = SubagentPermissionCatalog.categories
        .filter { it.risk == SubagentPermissionCategory.Risk.READ }
        .flatMapTo(linkedSetOf()) { it.tools }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EtaChoiceChip(
                text = "推荐只读",
                selected = selectedTools == readOnlyTools,
                onClick = { onSelectedToolsChange(readOnlyTools) },
            )
            EtaChoiceChip(
                text = "全部权限",
                selected = selectedTools.containsAll(SubagentPermissionCatalog.allTools),
                onClick = { onSelectedToolsChange(SubagentPermissionCatalog.allTools) },
            )
            EtaChoiceChip(
                text = "清空",
                selected = selectedTools.isEmpty(),
                onClick = { onSelectedToolsChange(emptySet()) },
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            SubagentPermissionCatalog.categories.forEach { category ->
                val allSelected = category.id in selectedCategories
                val partial = category.id in partialCategories
                val expanded = expandedCategory == category.id
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .squircleSurface(
                            color = when {
                                allSelected -> MiuixTheme.colorScheme.tertiaryContainer
                                else -> MiuixTheme.colorScheme.surfaceContainer
                            },
                            cornerRadius = 14.dp,
                        )
                        .clickable {
                            val next = if (allSelected) {
                                selectedTools - category.tools
                            } else {
                                selectedTools + category.tools
                            }
                            onSelectedToolsChange(next)
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            state = when {
                                allSelected -> ToggleableState.On
                                partial -> ToggleableState.Indeterminate
                                else -> ToggleableState.Off
                            },
                            onClick = {
                                onSelectedToolsChange(
                                    if (allSelected) selectedTools - category.tools else selectedTools + category.tools,
                                )
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = category.title,
                                style = MiuixTheme.textStyles.body1,
                                fontWeight = if (allSelected) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (allSelected) {
                                    MiuixTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MiuixTheme.colorScheme.onSurface
                                },
                            )
                            Text(
                                text = category.summary,
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        TextButton(
                            text = if (expanded) "收起" else "查看",
                            onClick = { expandedCategory = if (expanded) null else category.id },
                        )
                    }
                    Text(
                        text = when (category.risk) {
                            SubagentPermissionCategory.Risk.READ -> "只读 · ${category.tools.size} 项"
                            SubagentPermissionCategory.Risk.WRITE -> "写入 · ${category.tools.size} 项"
                            SubagentPermissionCategory.Risk.DESTRUCTIVE -> "高影响 · ${category.tools.size} 项"
                        },
                        style = MiuixTheme.textStyles.footnote2,
                        color = when (category.risk) {
                            SubagentPermissionCategory.Risk.READ -> MiuixTheme.colorScheme.primary
                            SubagentPermissionCategory.Risk.WRITE -> StatusError.copy(alpha = 0.82f)
                            SubagentPermissionCategory.Risk.DESTRUCTIVE -> StatusError
                        },
                        modifier = Modifier.padding(start = 56.dp),
                    )
                    if (expanded) {
                        FlowRow(
                            modifier = Modifier.padding(start = 52.dp, top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            category.tools.sorted().forEach { tool ->
                                EtaChoiceChip(
                                    text = tool,
                                    selected = tool in selectedTools,
                                    onClick = {
                                        onSelectedToolsChange(
                                            if (tool in selectedTools) selectedTools - tool else selectedTools + tool,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
            Text(
                text = "系统始终禁止递归派发、自修改 Subagent 和独占屏幕 GUI。选择分类只决定模型可调用的具体工具。",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .fillMaxWidth()
                    .squircleSurface(MiuixTheme.colorScheme.surfaceContainer, 12.dp)
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun SubagentModelPickerContent(
    pickerState: io.github.mangi.eta.ui.model.AgentModelPickerUiState,
    selectedProviderId: String?,
    selectedSecondaryModelId: String?,
    primaryModelName: String?,
    onSelectModel: (providerId: String?, modelId: String?) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var providerFilter by remember { mutableStateOf<String?>(null) }
    val filteredGroups = remember(pickerState.providerGroups, query, providerFilter) {
        pickerState.filterGroups(query)
            .filter { providerFilter == null || it.providerId == providerFilter }
    }
    val density = LocalDensity.current
    val sheetHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.68f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(sheetHeight)
            .navigationBarsPadding(),
    ) {
        InputField(
            query = query,
            onQueryChange = { query = it },
            onSearch = {},
            expanded = false,
            onExpandedChange = {},
            label = "搜索模型或服务商",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (pickerState.providerGroups.size > 1) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "filter:all") {
                    EtaChoiceChip(
                        text = "全部",
                        selected = providerFilter == null,
                        onClick = { providerFilter = null },
                    )
                }
                items(
                    items = pickerState.providerGroups,
                    key = { "filter:${it.providerId}" },
                ) { group ->
                    EtaChoiceChip(
                        text = group.providerName,
                        selected = providerFilter == group.providerId,
                        onClick = {
                            providerFilter = if (providerFilter == group.providerId) null else group.providerId
                        },
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (query.isBlank() && providerFilter == null) {
                item(key = "inherit_primary") {
                    SubagentModelRow(
                        title = "跟随主模型",
                        subtitle = primaryModelName?.let { "当前：$it" } ?: "默认继承会话正在使用的主模型",
                        modelId = pickerState.selectedModel?.modelId,
                        sourceType = pickerState.selectedModel?.providerSourceType,
                        selected = selectedSecondaryModelId == null,
                        onClick = { onSelectModel(null, null) },
                    )
                }
            }
            filteredGroups.forEach { group ->
                if (providerFilter == null) {
                    item(key = "header:${group.providerId}") {
                        Text(
                            text = group.providerName,
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp),
                        )
                    }
                }
                items(
                    items = group.models,
                    key = { model -> "model:${model.providerId}:${model.id}" },
                ) { model ->
                    SubagentModelOptionRow(
                        model = model,
                        selected = model.id == selectedSecondaryModelId &&
                            (selectedProviderId == null || model.providerId == selectedProviderId),
                        onClick = { onSelectModel(model.providerId, model.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SubagentModelOptionRow(
    model: AgentModelOptionUi,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SubagentModelRow(
        title = model.displayName,
        subtitle = model.modelId.takeIf { !it.equals(model.displayName, ignoreCase = true) },
        modelId = model.modelId,
        sourceType = model.providerSourceType,
        selected = selected,
        onClick = onClick,
    )
}

@Composable
private fun SubagentModelRow(
    title: String,
    subtitle: String?,
    modelId: String?,
    sourceType: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .squircleSurface(
                color = if (selected) {
                    MiuixTheme.colorScheme.tertiaryContainer
                } else {
                    MiuixTheme.colorScheme.surface.copy(alpha = 0f)
                },
                cornerRadius = 14.dp,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModelBrandMark(
            modelId = modelId,
            sourceType = sourceType,
            size = 20.dp,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.body1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MiuixTheme.colorScheme.onTertiaryContainer else MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubagentProfileDetailContent(
    profile: SubagentProfile,
    modelSummary: String,
    effortOptions: List<ReasoningEffort?>,
    effortLabels: List<String>,
    selectedEffortIndex: Int,
    toolPolicySummary: String,
    contextBudgetOptions: List<Int?>,
    contextBudgetLabels: List<String>,
    selectedContextBudgetIndex: Int,
    toolCallBudgetOptions: List<Int?>,
    toolCallBudgetLabels: List<String>,
    selectedToolCallBudgetIndex: Int,
    onConfigureModel: () -> Unit,
    onConfigureTools: () -> Unit,
    onSelectEffort: (ReasoningEffort?) -> Unit,
    onSelectContextBudget: (Int?) -> Unit,
    onSelectToolCallBudget: (Int?) -> Unit,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = profile.summary,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        ArrowPreference(
            title = "独立模型",
            summary = modelSummary,
            onClick = onConfigureModel,
        )
        if (effortOptions.size > 1) {
            OverlayDropdownPreference(
                title = "Profile 思考强度",
                items = effortLabels,
                selectedIndex = selectedEffortIndex,
                onSelectedIndexChange = { index ->
                    onSelectEffort(effortOptions.getOrNull(index))
                },
            )
        }
        ArrowPreference(
            title = "工具策略",
            summary = toolPolicySummary,
            onClick = onConfigureTools,
        )
        OverlayDropdownPreference(
            title = "上下文预算",
            items = contextBudgetLabels,
            selectedIndex = selectedContextBudgetIndex,
            onSelectedIndexChange = { index ->
                onSelectContextBudget(contextBudgetOptions.getOrNull(index))
            },
        )
        OverlayDropdownPreference(
            title = "工具调用预算",
            items = toolCallBudgetLabels,
            selectedIndex = selectedToolCallBudgetIndex,
            onSelectedIndexChange = { index ->
                onSelectToolCallBudget(toolCallBudgetOptions.getOrNull(index))
            },
        )
        Text(
            text = if (profile.builtin) profile.whenToUse else profile.roleInstruction,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Tools",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                profile.toolHighlights.forEach { toolName ->
                    Box(
                        modifier = Modifier
                            .squircleSurface(
                                color = MiuixTheme.colorScheme.surfaceContainer,
                                cornerRadius = 8.dp,
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = toolName,
                            style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
        if (onEdit != null || onDelete != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (onDelete != null) {
                    TextButton(
                        text = "删除",
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(
                            textColor = StatusError,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (onEdit != null) {
                    TextButton(
                        text = "编辑",
                        onClick = onEdit,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
