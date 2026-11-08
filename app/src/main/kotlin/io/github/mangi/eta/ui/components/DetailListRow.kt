package io.github.mangi.eta.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun DetailListRow(title: String, subtitle: String, icon: ImageVector,
    preview: String? = null, value: String? = null, enabled: Boolean = true,
    onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth()
        .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
        .alpha(if (enabled) 1f else 0.48f)
        .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        PreferenceIcon(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MiuixTheme.textStyles.body2.copy(fontSize = 15.sp, lineHeight = 21.sp), fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            preview?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MiuixTheme.textStyles.body2.copy(fontSize = 14.sp, lineHeight = 21.sp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(subtitle, style = MiuixTheme.textStyles.footnote2.copy(fontSize = 12.sp, lineHeight = 18.sp), color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            value?.let { Text(it, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.primary) }
        }
        if (onClick != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(16.dp).padding(top = 2.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
