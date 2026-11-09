package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared spacing and motion values for the current UI refinement pass. */
internal object EtaUiMetrics {
    val pageHorizontal = 12.dp
    val cardPadding = 16.dp
    val groupGap = 20.dp
    val cardGap = 8.dp
    val rowMinHeight = 56.dp
    val iconTile = 32.dp
    val iconGlyph = 18.dp
    val iconCorner = 10.dp
    val statusMinHeight = 28.dp
}

internal object EtaTypography {
    val sectionSize = 13.sp
    val sectionLineHeight = 18.sp
    val rowTitleSize = 16.sp
    val rowTitleLineHeight = 22.sp
    val valueSize = 14.sp
    val valueLineHeight = 20.sp
    val statusSize = 12.sp
    val statusLineHeight = 16.sp
    val supportingSize = 12.sp
    val supportingLineHeight = 18.sp
    val metricValueSize = 24.sp
    val metricValueLineHeight = 30.sp
}

internal object EtaMotion {
    const val press = 100
    const val state = 180
    const val content = 220
    const val chart = 420

    fun <T> standard(duration: Int = state) = tween<T>(
        durationMillis = duration,
        easing = FastOutSlowInEasing,
    )
}

internal enum class EtaIconTone(val accent: Color) {
    MODEL(Color(0xFF4C7CF3)),
    MEMORY(Color(0xFFE39B2D)),
    AUTOMATION(Color(0xFF8A67E8)),
    TOOL(Color(0xFF2AA6B8)),
    SYSTEM(Color(0xFF2AA978)),
    DATA(Color(0xFF6C7A89)),
    RISK(Color(0xFFE05A67)),
}

@Composable
internal fun EtaIconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tone: EtaIconTone = EtaIconTone.MODEL,
    accentColor: Color? = null,
    enabled: Boolean = true,
    size: Dp = EtaUiMetrics.iconTile,
    glyphSize: Dp = EtaUiMetrics.iconGlyph,
) {
    val accent = accentColor ?: tone.accent
    val tint = if (enabled) accent else MiuixTheme.colorScheme.disabledOnSecondaryVariant
    val container = if (enabled) accent.copy(alpha = 0.12f) else MiuixTheme.colorScheme.surfaceContainerHigh
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(EtaUiMetrics.iconCorner))
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(glyphSize))
    }
}

internal enum class EtaStatusTone { Neutral, Positive, Warning, Danger }

@Composable
internal fun EtaStatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: EtaStatusTone = EtaStatusTone.Neutral,
) {
    val color = when (tone) {
        EtaStatusTone.Neutral -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        EtaStatusTone.Positive -> EtaIconTone.SYSTEM.accent
        EtaStatusTone.Warning -> EtaIconTone.MEMORY.accent
        EtaStatusTone.Danger -> MiuixTheme.colorScheme.error
    }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = EtaUiMetrics.statusMinHeight)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 9.dp, vertical = 5.dp)
            .semantics { contentDescription = text },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = EtaTypography.statusSize,
            lineHeight = EtaTypography.statusLineHeight,
            fontWeight = FontWeight.Medium,
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
internal fun EtaInteractiveRow(
    interactionSource: MutableInteractionSource,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && pressed) 0.988f else 1f,
        animationSpec = tween(EtaMotion.press, easing = FastOutSlowInEasing),
        label = "eta-row-press",
    )
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = EtaUiMetrics.rowMinHeight)
            .graphicsLayer { scaleX = scale; scaleY = scale },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
internal fun rememberEtaInteractionSource(): MutableInteractionSource = remember { MutableInteractionSource() }

@Composable
internal fun etaRippleIndication(): androidx.compose.foundation.Indication = LocalIndication.current
