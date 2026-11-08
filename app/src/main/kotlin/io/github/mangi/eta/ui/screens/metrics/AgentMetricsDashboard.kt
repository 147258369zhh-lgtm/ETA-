package io.github.mangi.eta.ui.screens.metrics

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mangi.eta.agent.runtime.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate
import java.time.format.TextStyle

@Composable
internal fun AgentMetricsOverview(records: List<AgentRunMetricsRecord>, models: List<AgentMetricModelEntity>,
    metrics: List<AgentRunMetrics>, costAvailable: Boolean) {
    val costs = AgentTokenCostEstimator.summarize(models, models.size + records.count { run -> models.none { it.recordId == run.recordId } })
    val knownCost = costs.currencies.joinToString("\n") { "${it.currency} ${metricMoney(it.amount)}" }
    fun total(field: (AgentRunMetrics) -> Long?): Long? = metrics.mapNotNull(field).takeIf { it.isNotEmpty() }?.sum()
    val reads = metrics.filter { it.cacheReadTokens != null && it.uncachedInputTokens != null }
    val readTokens = reads.sumOf { it.cacheReadTokens ?: 0 }
    val allInput = reads.sumOf { (it.uncachedInputTokens ?: 0) + (it.cacheReadTokens ?: 0) + (it.cacheWriteTokens ?: 0) }
    val cacheRate = if (allInput > 0) "%.1f%%".format(100.0 * readTokens / allInput) else "—"
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            MetricValue("预估费用", if (costAvailable) knownCost.ifBlank { "暂无可估算费用" } else "暂不可用", Modifier.weight(1f))
            MetricValue("请求次数", metricNumber(total { it.requestAttempts.toLong() }), Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            MetricValue("已报告 Token", metricNumber(total { it.reportedTotalTokens() }), Modifier.weight(1f))
            MetricValue("输入缓存命中", cacheRate, Modifier.weight(1f))
        }
        Text("${records.size} 次运行 · ${metrics.count { it.usageComplete }}/${metrics.size} 项用量完整 · ${costs.completeModels}/${costs.totalModels} 项费用完整\n按运行时单价估算，缺失部分不计入费用。",
            style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable internal fun MetricValue(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, style = MiuixTheme.textStyles.body1.copy(fontSize = if (value.substringAfterLast('\n').length > 16) 15.sp else 19.sp, lineHeight = 25.sp), fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun AgentActivityCalendar(days: List<AgentActivityDay>, selected: LocalDate?, onSelect: (LocalDate?) -> Unit) {
    var mode by remember { mutableIntStateOf(0) }
    val maximum = days.maxOfOrNull { if (mode == 0) it.runs.toLong() else it.tokens ?: 0 }?.coerceAtLeast(1) ?: 1
    val weeks = days.chunked(7)
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("近 13 周", style = MiuixTheme.textStyles.footnote1)
            Text("连续 ${AgentMetricsActivity.streak(days)} 天", style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        TabRow(tabs = listOf("运行", "Token"), selectedTabIndex = mode, onTabSelected = { mode = it })
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val locale = LocalLocale.current.platformLocale
            val gap = 3.dp
            val dayLabelWidth = 12.dp
            val cellSize = ((maxWidth - dayLabelWidth - gap * weeks.size) / weeks.size)
                .coerceAtLeast(14.dp)
            val weekdayLabels = listOf(
                java.time.DayOfWeek.MONDAY,
                java.time.DayOfWeek.TUESDAY,
                java.time.DayOfWeek.WEDNESDAY,
                java.time.DayOfWeek.THURSDAY,
                java.time.DayOfWeek.FRIDAY,
                java.time.DayOfWeek.SATURDAY,
                java.time.DayOfWeek.SUNDAY,
            ).map { it.getDisplayName(TextStyle.NARROW, locale) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Column(
                    modifier = Modifier.width(dayLabelWidth),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    Spacer(Modifier.height(18.dp))
                    weekdayLabels.forEachIndexed { index, label ->
                        Text(
                            text = if (index % 2 == 0 || index == 6) label else "",
                            modifier = Modifier.height(cellSize).fillMaxWidth(),
                            style = MiuixTheme.textStyles.footnote2.copy(fontSize = 8.sp, lineHeight = 10.sp),
                            textAlign = TextAlign.End,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            maxLines = 1,
                        )
                    }
                }
                weeks.forEach { week ->
                    Column(
                        modifier = Modifier.width(cellSize),
                        verticalArrangement = Arrangement.spacedBy(gap),
                    ) {
                        val monthStart = week.firstOrNull { it.date.dayOfMonth == 1 }?.date
                            ?: week.first().date.takeIf { week == weeks.first() }
                        Text(
                            text = monthStart?.let { "${it.monthValue}月" }.orEmpty(),
                            modifier = Modifier.height(18.dp).fillMaxWidth(),
                            style = MiuixTheme.textStyles.footnote2.copy(fontSize = 9.sp, lineHeight = 12.sp),
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        repeat(7) { index ->
                            val day = week.getOrNull(index)
                            val value = if (mode == 0) day?.runs?.toLong() ?: 0 else day?.tokens ?: 0
                            val level = if (value == 0L) 0f else (value.toFloat() / maximum).coerceIn(.28f, 1f)
                            val fill = if (level == 0f) {
                                MiuixTheme.colorScheme.surfaceContainerHigh
                            } else {
                                MiuixTheme.colorScheme.primary.copy(alpha = .28f + .72f * level)
                            }
                            val isSelected = day?.date == selected
                            Box(
                                Modifier
                                    .size(cellSize)
                                    .background(
                                        if (day == null) androidx.compose.ui.graphics.Color.Transparent else fill,
                                        RoundedCornerShape(4.dp),
                                    )
                                    .then(
                                        if (isSelected) {
                                            Modifier.border(2.dp, MiuixTheme.colorScheme.onSurface, RoundedCornerShape(4.dp))
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .then(
                                        if (day != null) {
                                            Modifier
                                                .semantics {
                                                    contentDescription = "${day.date}，${day.runs} 次运行，${day.tokens?.toString() ?: "未知"} Token"
                                                }
                                                .clickable { onSelect(day.date.takeIf { it != selected }) }
                                        } else {
                                            Modifier
                                        },
                                    ),
                            )
                        }
                    }
                }
            }
        }
        val day = days.firstOrNull { it.date == selected }
        Text(day?.let { "${it.date} · ${it.runs} 次运行 · ${it.tokens?.toString() ?: "用量未知"} Token${if (!it.complete) " · 部分用量" else ""}" }
            ?: "按当前筛选与保留记录统计", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
