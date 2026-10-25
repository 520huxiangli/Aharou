package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors

/** 空态图标底板尺寸：与技能页、子代理页既有空态同档，避免每页各写一套。 */
private val IconTileSize = 64.dp

/** 底板内图标的视觉尺寸，约底板的一半，留出呼吸感。 */
private val IconGlyphSize = 28.dp

/**
 * 设置类页面的统一空态：圆角图标底板 + 标题 + 可选说明 + 可选操作区。
 *
 * 说明与操作区都可省：只有一句话可讲的列表用「图标 + 标题」即可，需要引导下一步的页面
 * 在 [actions] 里放按钮，按钮数量与主次由调用方决定。
 * 需要垂直居中时由调用方在 modifier 传 `fillMaxSize()`；在可滚动页面里不传则按内容高度排布。
 */
@Composable
internal fun SettingsEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(IconTileSize)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Radius.lg)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(IconGlyphSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.semanticColors.subtleText,
                textAlign = TextAlign.Center
            )
        }
        if (actions != null) {
            Spacer(Modifier.height(Spacing.lg))
            actions(this)
        }
    }
}
