package com.aharou.feature.agent.domain.mcp

import kotlinx.serialization.Serializable

// 远程 HTTP（含 url）或本地 stdio（含 command）两种形态，由 isStdio 推断。
@Serializable
data class McpServerConfig(
    val name: String,
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val command: String? = null,
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val disabledTools: Set<String> = emptySet()
) {
    val isStdio: Boolean get() = !command.isNullOrBlank()

    companion object {
        /**
         * 名称会拼进 function-calling 工具名（`mcp__{名称}__{工具名}`），只允许 ASCII 字母、数字、下划线与连字符；
         * 中文等其它 Unicode 字符不符合 provider 的工具命名规范。
         */
        private val NAME_REGEX = Regex("[a-zA-Z0-9_-]+")

        fun isValidName(name: String): Boolean = NAME_REGEX.matches(name)
    }
}
