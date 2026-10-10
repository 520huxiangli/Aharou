package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.identity.UserIdentity
import com.aharou.core.identity.UserIdentityStore
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

/**
 * 用户消息上方的身份行：名字 + 头像，右对齐。
 *
 * 与 [SoulChatHeaderRow] 互为镜像：那条读 SOUL.md、靠左；这条读本地用户身份、靠右。
 * 头像渲染共用 [SoulIconGlyph]，只把默认图标换掉——默认成助手的 ✨ 会让人分不清是谁在说话。
 */
@Composable
internal fun UserChatHeaderRow() {
    val context = LocalContext.current
    val identity by remember(context) { UserIdentityStore.observe(context) }
        .collectAsStateWithLifecycle(UserIdentity())
    val name = identity.name.trim().ifEmpty { stringResource(R.string.chat_user_default_name) }
    Row(
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
    ) {
        Text(
            text = name,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        SoulIconGlyph(
            icon = identity.icon,
            sizeDp = 16.dp,
            emojiSp = 13.sp,
            fallbackEmoji = USER_DEFAULT_ICON,
        )
    }
}

/** 用户头像的默认图标；与助手的 ✨ 区分开。 */
private const val USER_DEFAULT_ICON = "\uD83D\uDE42"
