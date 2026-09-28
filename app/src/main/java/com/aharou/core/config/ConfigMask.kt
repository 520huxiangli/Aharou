package com.aharou.core.config

/** 展示面上敏感值的统一占位文本。 */
const val CONFIG_MASK_TEXT = "••• (hidden)"

/**
 * 敏感字段在展示面（确认弹窗、审计页）的打码。
 *
 * **只用于显示**：审计表里存的仍是真值，因为撤销（[com.aharou.core.config.audit.ConfigRevert]）
 * 要读旧值回写；在存储层调用它会让撤销把占位文本写回真实设置。
 */
fun ConfigValue.maskedForDisplay(sensitive: Boolean): ConfigValue =
    if (sensitive) ConfigValue.Str(CONFIG_MASK_TEXT) else this

/** 按字段风险判断该值是否需要在展示面上打码。未知字段（已删）按非敏感处理。 */
fun isSensitivePath(path: String): Boolean =
    runCatching { ConfigRegistry.get().resolveField(path)?.risk }
        .getOrNull() == ConfigRisk.SENSITIVE
