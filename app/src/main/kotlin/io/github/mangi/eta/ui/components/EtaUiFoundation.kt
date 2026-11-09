package io.github.mangi.eta.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
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
    const val card = 260
    const val chart = 420

    fun <T> standard(duration: Int = state) = tween<T>(
        durationMillis = duration,
        easing = FastOutSlowInEasing,
    )

    fun <T> pressSpring() = spring<T>(
        dampingRatio = 0.58f,
        stiffness = Spring.StiffnessMediumLow,
    )

    fun <T> layoutSpring() = spring<T>(
        dampingRatio = 0.86f,
        stiffness = Spring.StiffnessMediumLow,
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
    val shape = RoundedCornerShape(EtaUiMetrics.iconCorner)
    val container = if (enabled) {
        Brush.linearGradient(listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.08f)))
    } else {
        Brush.linearGradient(
            listOf(MiuixTheme.colorScheme.surfaceContainerHigh, MiuixTheme.colorScheme.surfaceContainer),
        )
    }
    val rim = if (enabled) accent.copy(alpha = 0.20f) else MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.55f)
    Box(
        modifier = modifier
            .size(size)
            .shadow(if (enabled) 4.dp else 0.dp, shape, ambientColor = accent.copy(alpha = 0.16f), spotColor = accent.copy(alpha = 0.16f))
            .clip(shape)
            .background(container)
            .border(0.7.dp, rim, shape)
            .drawWithContent {
                drawContent()
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                        endY = this.size.height * 0.62f,
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(EtaUiMetrics.iconCorner.toPx()),
                )
            },
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
    val container by animateColorAsState(color.copy(alpha = 0.11f), tween(EtaMotion.state, easing = FastOutSlowInEasing), label = "eta-status-container")
    val border by animateColorAsState(color.copy(alpha = 0.18f), tween(EtaMotion.state, easing = FastOutSlowInEasing), label = "eta-status-border")
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = EtaUiMetrics.statusMinHeight)
            .clip(RoundedCornerShape(50))
            .background(container)
            .border(0.7.dp, border, RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 5.dp)
            .semantics { contentDescription = text },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = text,
            animationSpec = EtaMotion.standard(),
            label = "eta-status-content",
        ) { current ->
            Text(
                text = current,
                fontSize = EtaTypography.statusSize,
                lineHeight = EtaTypography.statusLineHeight,
                fontWeight = FontWeight.Medium,
                color = color,
                maxLines = 1,
            )
        }
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
        targetValue = if (enabled && pressed) 0.982f else 1f,
        animationSpec = EtaMotion.pressSpring(),
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

/**
 * Shared card finish: one restrained rim, a top specular sheen and a small theme-tinted shadow.
 * The effect stays below text and remains legible in both light and dark themes.
 */
@Composable
internal fun Modifier.etaGlassSurface(
    accent: Color = MiuixTheme.colorScheme.primary,
    cornerRadius: Dp = 18.dp,
): Modifier {
    val colors = MiuixTheme.colorScheme
    val shape = RoundedCornerShape(cornerRadius)
    val dark = colors.surface.luminance() < 0.42f
    val highlight = Color.White.copy(alpha = if (dark) 0.075f else 0.34f)
    return this
        .shadow(
            elevation = if (dark) 5.dp else 3.dp,
            shape = shape,
            ambientColor = accent.copy(alpha = if (dark) 0.20f else 0.10f),
            spotColor = accent.copy(alpha = if (dark) 0.24f else 0.12f),
            clip = false,
        )
        .border(
            width = 0.7.dp,
            brush = Brush.linearGradient(
                listOf(highlight, accent.copy(alpha = if (dark) 0.10f else 0.06f), colors.dividerLine.copy(alpha = 0.18f)),
            ),
            shape = shape,
        )
        .drawWithContent {
            drawContent()
            drawRoundRect(
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = if (dark) 0.016f else 0.045f), Color.Transparent),
                    endY = size.height * 0.42f,
                ),
                size = Size(size.width, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius.toPx()),
            )
        }
}

/** Quiet page atmosphere that sits behind cards without reducing text contrast. */
@Composable
internal fun Modifier.etaPageAtmosphere(): Modifier {
    val colors = MiuixTheme.colorScheme
    val dark = colors.surface.luminance() < 0.42f
    val primary = colors.primary
    val secondary = colors.secondary
    return drawWithContent {
        drawRect(
            Brush.verticalGradient(
                listOf(
                    colors.surface,
                    colors.surfaceContainer.copy(alpha = if (dark) 0.30f else 0.55f),
                    colors.surface,
                ),
            ),
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(primary.copy(alpha = if (dark) 0.13f else 0.075f), Color.Transparent),
                center = Offset(size.width * 0.88f, size.height * 0.04f),
                radius = size.minDimension * 0.72f,
            ),
            radius = size.minDimension * 0.72f,
            center = Offset(size.width * 0.88f, size.height * 0.04f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                listOf(secondary.copy(alpha = if (dark) 0.08f else 0.045f), Color.Transparent),
                center = Offset(size.width * 0.08f, size.height * 0.88f),
                radius = size.minDimension * 0.56f,
            ),
            radius = size.minDimension * 0.56f,
            center = Offset(size.width * 0.08f, size.height * 0.88f),
        )
        val step = 24.dp.toPx()
        val dot = Color.White.copy(alpha = if (dark) 0.025f else 0.16f)
        var x = step / 2
        while (x < size.width) {
            var y = step / 2
            while (y < size.height) {
                drawCircle(dot, radius = 0.55.dp.toPx(), center = Offset(x, y))
                y += step
            }
            x += step
        }
        drawContent()
    }
}
