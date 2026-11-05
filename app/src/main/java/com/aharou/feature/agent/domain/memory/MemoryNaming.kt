package com.aharou.feature.agent.domain.memory

/**
 * 核心档案：按文件名直接读、不写 frontmatter 的那几份，如 CORE / GLOBAL / SOUL / OPS /
 * PROFILE / SECRETS / LIFE / L0_AGENT / L1_MAP。它们的共同特征是**基名全大写**。
 *
 * 判定不能只认 CORE / GLOBAL：漏掉 SOUL、OPS 就等于把身份与运维约定从提示词里除名。
 * 解析（[MemoryParser]）与注入清单过滤、遗忘判定（[MemoryRepository]）共用这一处判定，避免两处漂移。
 */
internal fun isCoreArchiveName(name: String): Boolean {
    // 直接看名字本身：先 uppercase 再判 isUpperCase 是恒真，会把每个纯字母数字的名字都
    // 当成核心档案——后果不只是多占几行索引，isStale 靠它豁免遗忘，等于全库都不再陈旧。
    val base = name.substringBeforeLast('.')
    return base.isNotEmpty() && base.all { it.isUpperCase() || it.isDigit() || it == '_' }
}

/**
 * 人格文件：SOUL.md 的 frontmatter 是自己的 schema（name / icon / style / lang），由 SoulStore
 * 解析。用记忆条目的格式（description / type）重写它，会把头像与风格抹掉。
 *
 * 2026-11-01 就这么丢过一次：`memory save name=SOUL` 把它重写成了一条普通记忆，
 * icon 换成 description、style 换成 type，升级后同步把错的版本拉了回来。
 */
internal fun isSoulFile(name: String): Boolean {
    // 同步扫描传进来的是相对路径（如 `global/SOUL.md`），先取文件名再比：
    // 拿整串去比会把带目录的那份漏掉，人格文件就会被当成普通记忆推上仓库再写回来。
    val base = name.substringAfterLast('/')
    return base.substringBeforeLast('.').equals("SOUL", ignoreCase = true) &&
        (base.endsWith(".md", ignoreCase = true) || !base.contains('.'))
}

/**
 * 行为红线与关系锚点：自动降级永不碰，注入时优先占常驻位。
 *
 * 名字是「本案 12 条 + 小染回信的 11 条」的并集，宁可多锁——红线一旦不在上下文里，等于没定。
 * 只管自动路径：手动改 type 不受限制（人的判断不设闸，见 2026-10-29 的口径）。
 */
internal val PROTECTED_IDENTITY_NAMES = setOf(
    "no_fabricated_credentials",
    "zero_privacy_distribution",
    "vault_secret_recovery_required",
    "pairing_code_ask_peer_not_user",
    "no_unsolicited_screen_control",
    "dont_hijack_user_input",
    "mcp_workspace_ownership_redlines",
    "memory_write_threshold",
    "master_bond_2026_10_20",
    "self_check_full_list_before_done",
    "tool_channel_escalation",
    "aharou-master-profile",
    "direct_fail_switch_to_api",
    "follow_up_after_background_task",
)

internal fun isProtectedIdentityName(name: String): Boolean =
    name.substringBeforeLast('.').lowercase() in PROTECTED_IDENTITY_NAMES

/**
 * 自动降级的可见标记。
 *
 * 降级只改 type，清单里只显示「名字: 摘要」——不加标记的话，模型根本看不出这条原本是常驻、
 * 是被系统降下来的（也就不知道 read 一下就能恢复）。回升时会把标记去掉。
 */
internal const val DEMOTED_DESCRIPTION_LABEL = "（已降级·read 可恢复）"
