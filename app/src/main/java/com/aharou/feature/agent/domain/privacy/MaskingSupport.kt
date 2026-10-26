package com.aharou.feature.agent.domain.privacy

import com.aharou.core.privacy.OutboundMasker
import com.aharou.feature.agent.domain.model.AgentMessage

/**
 * 旁路请求（压缩、记忆整理、标题、提交信息）共用的小工具。
 *
 * 主对话链路的脱敏直接写在 workflow 里；这些一次性调用散在不同类中，统一用这里的三个函数，
 * 免得每个调用点各写一遍开关判断与空值处理。
 */
fun outboundMaskerOrNull(enabled: Boolean): OutboundMasker? =
    if (enabled) OutboundMasker.create() else null

/** 掩码消息列表；[masker] 为空时原样返回（关闭脱敏时零开销）。 */
fun List<AgentMessage>.maskIfNeeded(masker: OutboundMasker?): List<AgentMessage> =
    if (masker == null) this else maskedForOutbound(masker)

/** 掩码单段文本；[masker] 为空时原样返回。 */
fun String.maskIfNeeded(masker: OutboundMasker?): String =
    masker?.mask(this) ?: this

/** 回填单段文本；[masker] 为空时原样返回。 */
fun String.restoreIfNeeded(masker: OutboundMasker?): String =
    masker?.restore(this) ?: this
