package com.aharou.feature.im.domain

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 机器人归属开关：日常模式由 MaiBot 回消息，工作模式由 Aharou 自己回。
 *
 * 只控制「谁处理消息」，不控制 NapCat 进程——两种模式下 QQ 客户端都必须在线，
 * MaiBot 也是靠它的 OneBot 通道收发。
 */
@Singleton
class BotModeState @Inject constructor() {
    /** true 表示 Aharou 这边让出消息，交给 MaiBot 处理。 */
    @Volatile
    var handedToMaiBot: Boolean = false
}
