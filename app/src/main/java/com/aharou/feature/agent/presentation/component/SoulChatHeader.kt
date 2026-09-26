package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.core.soul.SoulMetadata
import com.aharou.core.soul.SoulStore
import com.aharou.feature.settings.presentation.component.SoulIconGlyph

/**
 * 助手消息上方的身份行：Soul 图标 + 名字。
 *
 * 数据源 [SoulStore.cachedMetadata]（StateFlow）：设置页保存或 Agent 经配置通道
 * 改名后，这一行立刻跟着变，不用重启。图标渲染与设置页预览共用 [SoulIconGlyph]，
 * 保证两处不会画得不一样。
 */
@Composable
internal fun SoulChatHeaderRow() {
    val meta by SoulStore.cachedMetadata.collectAsStateWithLifecycle()
    val name = meta.name.trim().ifEmpty { SoulMetadata.DEFAULT.name }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    ) {
        SoulIconGlyph(icon = meta.icon, sizeDp = 16.dp, emojiSp = 13.sp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = name,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
