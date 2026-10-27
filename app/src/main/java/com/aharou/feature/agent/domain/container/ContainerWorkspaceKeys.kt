package com.aharou.feature.agent.domain.container

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 「工作区路径 → 容器 profile」覆盖表的键口径。
 *
 * 写入侧（[com.aharou.feature.settings.data.repository.ContainerSettingsRepository]）与读取侧
 * （[LinuxContainerEngine]）必须用同一套归一化，否则用户在配置通道里配了覆盖却匹配不上——
 * 同一个工作区带尾斜杠、走符号链接、或中间有 `..` 时会被当成另一个工作区。
 *
 * 归一化 = 去首尾空白 + 去尾斜杠 + 解析成真实绝对路径（消掉符号链接与 `..`）。路径不存在时
 * `canonicalPath` 仍会按词法解析出结果（不抛异常），故对尚未创建的工作区也稳定。
 */
object ContainerWorkspaceKeys {

    /**
     * 归一化结果缓存：`canonicalPath` 走文件系统，而工作区路径会随每条命令进入解析，
     * 没必要每次都查盘。键是原始字符串、值是归一化结果，都是不可变 String，
     * 用 [ConcurrentHashMap] 保证多工作区并发解析安全；条目数上限等于用户用过的工作区路径数。
     */
    private val cache = ConcurrentHashMap<String, String>()

    /**
     * 在覆盖表里找 [projectPath] 的归属：先按自身路径精确匹配，没命中就逐级往上找父目录。
     *
     * 上溯是必需的：覆盖表的键是工作区根，而调用方传进来的不一定是根——Git 页选中子仓库时
     * `repoCwd()` 传的就是 `<工作区>/<子目录>`，pre-commit 检查同理。
     */
    fun overrideFor(overrides: Map<String, String>, projectPath: String?): String? {
        var path = normalize(projectPath) ?: return null
        while (true) {
            overrides[path]?.let { return it }
            val parent = path.substringBeforeLast('/', "")
            if (parent.isEmpty() || parent == path) return null
            path = parent
        }
    }

    /** 归一化为覆盖表的键；空路径返回 null（表示「没有工作区，用全局默认」）。 */
    fun normalize(workspacePath: String?): String? {
        val raw = workspacePath?.trim().orEmpty()
        if (raw.isEmpty()) return null
        cache[raw]?.let { return it }
        // 根目录 "/" 去尾斜杠会变成空串，保留原样。
        val stripped = if (raw.length > 1) raw.trimEnd('/').ifEmpty { "/" } else raw
        val normalized = runCatching { File(stripped).canonicalPath.trimEnd('/') }
            .getOrNull()
            ?.ifEmpty { "/" }
            ?: stripped
        cache[raw] = normalized
        return normalized
    }
}
