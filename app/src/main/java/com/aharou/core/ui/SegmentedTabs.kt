package com.aharou.core.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aharou.core.theme.semanticColors

/**
 * iOS 风分段控件：中性灰轨道 + 白色（深色下为抬起灰）滑块，选中时滑块弹性滑到对应一段。
 *
 * 不用主题色填充，让它与下方的扫描式列表协调，只靠中性色与轻阴影表现层次。
 * 侧边栏顶部与 MCP 编辑弹窗共用同一份，切换手感保持一致。
 */
@Composable
fun SegmentedTabs(
    selected: Int,
    labels: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val trackColor = MaterialTheme.semanticColors.mutedSurface
    val thumbColor = if (dark) MaterialTheme.semanticColors.capsuleSurface else MaterialTheme.semanticColors.cardSurface

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(trackColor)
            .padding(3.dp)
    ) {
        val thumbWidth = maxWidth / labels.size
        // 选中滑块位移：临界阻尼弹簧（不过冲），切换时滑块顺滑到位，不是硬切。
        val thumbOffset by animateDpAsState(
            targetValue = thumbWidth * selected,
            animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
            label = "segmented-thumb"
        )
        Surface(
            modifier = Modifier
                .offset(x = thumbOffset)
                .width(thumbWidth)
                .fillMaxHeight(),
            shape = RoundedCornerShape(9.dp),
            color = thumbColor,
            shadowElevation = 2.dp,
            content = {}
        )
        Row(modifier = Modifier.fillMaxSize()) {
            labels.forEachIndexed { index, label ->
                val isSelected = selected == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(9.dp))
                        // 禁用点击波纹：选中滑块位移已是反馈，ripple 残留的深色阴影反而扎眼。
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
