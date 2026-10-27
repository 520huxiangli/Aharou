package com.aharou.feature.agent.presentation.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.aharou.feature.settings.data.ExperimentSettings

/** 引用草稿：把消息首行摘要以引用块形式接到当前草稿前面。 */
internal fun buildQuoteDraft(content: String, draft: String): String {
    val excerpt = content.trim().lineSequence().firstOrNull().orEmpty().take(120)
    return "> " + excerpt + "\n\n" + draft
}

/** 聊天页读「长按引用」实验开关；开关改动需重启 App 生效。 */
@Composable
internal fun rememberQuoteReplyEnabled(): Boolean {
    val context = LocalContext.current
    return remember {
        ExperimentSettings(context.applicationContext).isEnabled("ui.quote_reply", true)
    }
}
