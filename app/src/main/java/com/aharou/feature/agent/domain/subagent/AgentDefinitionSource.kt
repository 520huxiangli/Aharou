package com.aharou.feature.agent.domain.subagent

import com.aharou.core.util.FileLogger
import com.aharou.feature.workspace.domain.FileAccessProvider

/** 子代理定义来源：一个目录下的 `*.md`，每个文件一个 agent。 */
interface AgentDefinitionSource {
    fun listDefinitions(): List<AgentDefinition>
}

/** 目录扫描：只取顶层 `*.md`，避免把技能目录等无关内容误当 agent 定义。 */
internal object AgentDefinitionDirectoryScanner {
    private const val TAG = "AgentDefinitionScanner"

    /**
     * 扫描失败（远程 SSH 断连、目录不可读）降级为“没有定义”，
     * 不让 IOException 冒泡成未捕获异常（以前会直接弹全局崩溃页）。
     */
    fun scan(provider: FileAccessProvider, root: String): List<AgentDefinition> =
        runCatching {
            if (!provider.isDirectory(root)) {
                emptyList()
            } else {
                val base = root.trimEnd('/')
                provider.listFiles(root)
                    .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
                    .mapNotNull { entry -> AgentDefinitionParser.parse(provider, "$base/${entry.name}") }
                    .sortedBy { it.name.lowercase() }
            }
        }.getOrElse { e ->
            FileLogger.w(TAG, "扫描子代理目录失败（$root）：${e.message}")
            emptyList()
        }
}
