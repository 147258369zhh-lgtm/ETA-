package io.github.mangi.eta.ui.screens.metrics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentMetricDay
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AgentMetricsTrendCard(days: List<AgentMetricDay>) {
    if (days.isEmpty()) return
    val maxRuns = days.maxOf { it.runs }.coerceAtLeast(1)
    val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
    Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.metrics_trend_range, days.first().date.format(dateFormat), days.last().date.format(dateFormat)),
                style = MiuixTheme.textStyles.body2)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                days.forEach { day ->
                    val description = stringResource(R.string.metrics_trend_description, day.date.format(dateFormat), day.runs,
                        metricNumber(day.inputTokens), metricNumber(day.outputTokens), day.incompleteUsage)
                    Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = description },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(NumberFormat.getIntegerInstance().format(day.runs), style = MiuixTheme.textStyles.footnote1)
                        Box(Modifier.height(100.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                            Box(Modifier.fillMaxWidth().height((100f * day.runs / maxRuns).coerceAtLeast(2f).dp)
                                .background(if (day.runs > 0) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(6.dp)))
                        }
                        Text(day.date.dayOfMonth.toString(), style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
            Text(stringResource(R.string.metrics_trend_note), style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}
