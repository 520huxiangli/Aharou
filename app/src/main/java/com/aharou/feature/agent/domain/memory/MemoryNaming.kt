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
