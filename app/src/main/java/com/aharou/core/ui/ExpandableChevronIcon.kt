package com.aharou.core.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aharou.core.theme.Brand
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight

/**
 * 折叠箭头的旋转语义风格：
 * - [UP_DOWN]: 基准朝下 (ChevronDown)，收起为 0°，展开翻转 180° 朝上；用于卡片内部折叠、思考面板、顶栏看板等；
 * - [RIGHT_DOWN]: 基准朝右 (ChevronRight)，收起为 0°，展开顺时针旋转 90° 朝下；用于整轮执行过程、侧边栏时间分组、Git 状态分组等。
 */
enum class ChevronRotationStyle {
    UP_DOWN,
    RIGHT_DOWN,
}

private const val CHEVRON_ANIM_DURATION_MS = 200

/**
 * 统一折叠小箭头组件：封装平滑的旋转动画与统一的时长/缓动曲线。
 */
@Composable
fun ExpandableChevronIcon(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    style: ChevronRotationStyle = ChevronRotationStyle.UP_DOWN,
    size: Dp = 18.dp,
    tint: Color = Brand.IconGray,
    contentDescription: String? = null
) {
    val targetRotation = when (style) {
        ChevronRotationStyle.UP_DOWN -> if (expanded) 180f else 0f
        ChevronRotationStyle.RIGHT_DOWN -> if (expanded) 90f else 0f
    }
    val rotation by animateFloatAsState(
        targetValue = targetRotation,
        animationSpec = tween(durationMillis = CHEVRON_ANIM_DURATION_MS, easing = FastOutSlowInEasing),
        label = "chevron_rotation"
    )
    val baseIcon: ImageVector = when (style) {
        ChevronRotationStyle.UP_DOWN -> FeatherIcons.ChevronDown
        ChevronRotationStyle.RIGHT_DOWN -> FeatherIcons.ChevronRight
    }
    Icon(
        imageVector = baseIcon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier
            .size(size)
            .rotate(rotation)
    )
}
