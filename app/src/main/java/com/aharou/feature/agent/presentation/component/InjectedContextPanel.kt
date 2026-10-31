package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet

/**
 * 用户消息气泡右下角的「显示全部」入口。
 *
 * 主人能看到的世界里只有自己打的字；模型实际还收到了一大段系统每轮自动拼进去的内容
 * （人格、规则、记忆、技能索引、当前时间…）。这个按钮把这些「不是你发的、但模型确实读到了」
 * 的文本摊开给主人看。
 *
 * 整块（按钮 + 弹层）自包含，宿主气泡只接一个 `text`，不必管展开状态；
 * 也是为了让已经贴着行数上限的 [MessageBubbles] 只多一行调用。
 */
@Composable
internal fun InjectedContextButton(text: String) {
    var open by remember { mutableStateOf(false) }
    Text(
        text = stringResource(R.string.chat_show_injected),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { open = true }
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
    if (open) {
        InjectedContextSheet(text = text, onDismiss = { open = false })
    }
}

/**
 * 「本轮默认注入」面板：标题 + 一句解释 + 正文。
 *
 * 正文用 [ExpandableOutputText] 而不是普通 Text：系统提示词动辄几万字符，
 * 那个组件自带「超长只渲染尾部预览 + 分段展开」的护栏，正是为这种体量写的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InjectedContextSheet(text: String, onDismiss: () -> Unit) {
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
        ) {
            Text(
                text = stringResource(R.string.chat_injected_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.chat_injected_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.md))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                ExpandableOutputText(
                    text = text,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
