package com.aharou.core.config.confirm

import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigValue

/**
 * AUTO 模式下「非敏感的配置修改不再弹确认面板」的敏感性判定，以及把它装到
 * [ConfigConfirmationGate] 上的入口。
 *
 * 权限引擎在 AUTO 模式下本来就全放行（`ToolPermissionPolicyEngine.evaluate` 里
 * `if (mode == AUTO) return ALLOW`），而 `config` 工具另有一道独立的确认闸门——
 * 两者语义此前是冲突的：用户以为「AUTO 什么都不用管」，实际改个主题、改个模型还会被弹窗拦一次。
 *
 * 这里的边界：
 * - 只有 **所有变更项都不敏感** 才自动批准（见 [isAutoApprovable]）；
 * - 敏感字段（API Key / 密码 / 凭据，以及风险档非 NORMAL 的字段）与集合增删照旧弹窗；
 * - 读明文（`reveal`）根本不参与自动批准（请求自带 `alwaysConfirm`）。
 *
 * 分工：[ConfigConfirmationGate] 只认「这次请求的会话是不是 AUTO + 策略说不敏感」，
 * 本对象负责后者。模式本身由 `config` 工具从 `AgentContext.mode` 按调用传入，
 * 因此多会话并行时每个请求按自己会话的模式判定。
 */
object ConfigAutoApprovePolicy {

    /**
     * 路径段里的敏感标记。基线直接复用 [ConfigValue.SECRET_KEYS]（它本来是给对象 key
     * 用的 camelCase 集合，如 `apiKey` / `apiKeys` / `token` / `password`），再补上配置
     * 路径里实际出现的几种写法（密钥、口令、凭据）。比对前统一小写并去掉分隔符，
     * 所以 `api_key` / `apiKey` / `API-KEY` 都会命中 `apikey`。
     */
    private val SENSITIVE_TOKENS: List<String> = buildList {
        addAll(ConfigValue.SECRET_KEYS.map { normalize(it) })
        addAll(
            listOf(
                "secret",
                "secrets",
                "credential",
                "credentials",
                "privatekey",
                "passwd",
                "accesstoken",
                "refreshtoken",
            ),
        )
    }.filter { it.isNotEmpty() }.distinct()

    /**
     * 把判定装到闸门上（幂等）。调用点在 `ConfigTool` 的 companion init：闸门只被 config
     * 工具用到，工具类加载必早于任何 `requestConfirmation`，而 AIEditorApp 已顶到 1000 行
     * 门禁、放不下启动注入那一行。闸门保持无 Context 依赖，策略可单独替换 / 单测。
     */
    fun install() {
        ConfigConfirmationGate.autoApprovePolicy = { change -> isAutoApprovable(change) }
    }

    /**
     * 这次请求能否自动批准：**所有**变更项都不敏感才算。
     * 保守优先——只要有一项风险档非 NORMAL（敏感 / 破坏性），或路径里带敏感键，
     * 就照旧弹窗让用户看。
     */
    fun isAutoApprovable(change: PendingConfigChange): Boolean {
        if (change.alwaysConfirm) return false
        if (change.items.isEmpty()) return false
        return change.items.all { item ->
            item.risk == ConfigRisk.NORMAL && !isSensitivePath(item.path)
        }
    }

    /** `providers.<id>.api_key` 这类路径：任一段落在敏感标记上即视为敏感。 */
    fun isSensitivePath(path: String): Boolean = path.split('.').any { segment ->
        val n = normalize(segment)
        n.isNotEmpty() && SENSITIVE_TOKENS.any { n.contains(it) }
    }

    private fun normalize(token: String): String = token.lowercase().replace("_", "").replace("-", "")
}
