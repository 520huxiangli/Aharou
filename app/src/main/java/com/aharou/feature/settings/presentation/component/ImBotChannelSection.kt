package com.aharou.feature.settings.presentation.component

import androidx.compose.runtime.Composable
import com.aharou.core.util.FileLogger

/**
 * IM 渠道设置分区按 [SettingsSection] 分流。
 *
 * 单列一处，是因为 SettingsScreen 已顶到文件行数上限，每加一个渠道都要往里塞分支；
 * 分发表放这里后，新增渠道只动本文件。
 *
 * 不写 `else -> Unit` 静默兜底：调用方只会传下面六个 section，落到别的值意味着
 * 有新渠道进了菜单却漏了分发——记一条错误日志，比界面上一片空白更好查。
 */
@Composable
internal fun ImBotChannelSection(section: SettingsSection) {
    when (section) {
        SettingsSection.TelegramBot -> TelegramBotSection()
        SettingsSection.WeixinBot -> WeixinBotSection()
        SettingsSection.FeishuBot -> FeishuBotSection()
        SettingsSection.QqBot -> QqBotSection()
        SettingsSection.DingTalkBot -> DingTalkBotSection()
        SettingsSection.WeComBot -> WeComBotSection()
        else -> FileLogger.e(TAG, "未分发的 IM 分区：$section")
    }
}

private const val TAG = "ImBotChannelSection"
