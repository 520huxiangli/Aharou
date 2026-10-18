package com.aharou.feature.im.domain

import kotlinx.coroutines.flow.Flow

/**
 * 编排层需要的配置来源：当前配置 + 轮询游标。
 *
 * 各平台配置字段不同（Telegram 只有 token，微信还要 ilinkUserId），所以这里只暴露
 * 通用的 [ImChannelConfig]；游标统一用字符串，Telegram 的 update_id 转成字符串也能装下。
 */
interface ImSettingsSource {

    /** 配置流，设置界面据此刷新开关与凭证状态。 */
    val configFlow: Flow<ImChannelConfig>

    /** 读一次当前配置（轮询循环每轮读取，改配置即时生效）。 */
    suspend fun currentConfig(): ImChannelConfig

    /** 上次处理到的游标；首次为空串。 */
    suspend fun lastCursor(): String

    suspend fun saveCursor(cursor: String)

    /** 服务重启后是否已经处理过历史消息（首次运行要丢弃积压）。 */
    suspend fun isPrimed(): Boolean = lastCursor().isNotBlank()
}
