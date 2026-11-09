package io.github.mangi.eta.ui.screens.enhance

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.app.description
import io.github.mangi.eta.ui.app.rememberDeviceCapabilities
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.EtaIconTile
import io.github.mangi.eta.ui.components.EtaIconTone
import io.github.mangi.eta.ui.components.EtaStatusPill
import io.github.mangi.eta.ui.components.EtaStatusTone
import io.github.mangi.eta.ui.model.AgentSystemEnhanceAction
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SystemEnhanceScreen(
    onAction: (AgentSystemEnhanceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val capabilities = rememberDeviceCapabilities()
    val canRequestRoot = capabilities.root.suPresent && !capabilities.root.isGranted
    MiuixScaffoldPage(
        title = stringResource(R.string.capability_enhancements),
        onBack = { onAction(AgentSystemEnhanceAction.NavigateBack) },
        modifier = modifier,
    ) {
        item(key = "overview") {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    EtaIconTile(Icons.Rounded.AdminPanelSettings, tone = EtaIconTone.RISK, size = 40.dp, glyphSize = 21.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("系统接管", style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                capabilities.root.isGranted && capabilities.xposedConnected -> "Root 与系统服务均可用"
                                capabilities.root.isGranted -> "Root 可用，系统服务未连接"
                                capabilities.xposedConnected -> "系统服务可用，Root 未授权"
                                else -> "当前使用标准 Android 权限"
                            },
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    EtaStatusPill(
                        if (capabilities.root.isGranted || capabilities.xposedConnected) "增强" else "标准",
                        tone = if (capabilities.root.isGranted || capabilities.xposedConnected) EtaStatusTone.Positive else EtaStatusTone.Neutral,
                    )
                }
            }
        }
        item(key = "access") {
            SmallTitle("访问能力")
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                BasicComponent(
                    title = "Root",
                    summary = capabilities.root.description(context),
                    endActions = {
                        TextButton(
                            text = stringResource(
                                if (canRequestRoot) R.string.capability_root_request
                                else R.string.capability_root_refresh,
                            ),
                            enabled = !capabilities.root.isChecking,
                            minWidth = 0.dp,
                            minHeight = 32.dp,
                            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            onClick = {
                                onAction(
                                    if (canRequestRoot) AgentSystemEnhanceAction.RequestRoot
                                    else AgentSystemEnhanceAction.RefreshRoot,
                                )
                            },
                        )
                    },
                )
                BasicComponent(
                    title = stringResource(R.string.capability_xposed_service),
                    summary = stringResource(
                        if (capabilities.xposedConnected) R.string.capability_xposed_connected
                        else R.string.capability_xposed_disconnected,
                    ),
                )
            }
        }
        item(key = "framework-help") {
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                BasicComponent(
                    title = stringResource(R.string.capability_xposed_help),
                    summary = stringResource(R.string.capability_xposed_help_summary),
                )
            }
        }
        item(key = "root-features") {
            SmallTitle(stringResource(R.string.capability_root_features))
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                BasicComponent(
                    title = stringResource(R.string.capability_root_device),
                    summary = stringResource(R.string.capability_root_device_summary),
                )
                BasicComponent(
                    title = stringResource(R.string.capability_root_data),
                    summary = stringResource(R.string.capability_root_data_summary),
                )
                BasicComponent(
                    title = stringResource(R.string.capability_root_linux),
                    summary = stringResource(R.string.capability_root_linux_summary),
                )
            }
        }
        item(key = "hook-features") {
            SmallTitle(stringResource(R.string.capability_system_features))
            Card(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp),
            ) {
                BasicComponent(
                    title = stringResource(R.string.capability_hook_assistants),
                    summary = stringResource(R.string.capability_hook_assistants_summary),
                )
                BasicComponent(
                    title = stringResource(R.string.capability_hook_google),
                    summary = stringResource(R.string.capability_hook_google_summary),
                )
                BasicComponent(
                    title = stringResource(R.string.capability_hook_accessibility),
                    summary = stringResource(R.string.capability_hook_accessibility_summary),
                )
            }
        }
    }
}
