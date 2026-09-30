package com.aharou.feature.agent.domain.memory

import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import com.aharou.feature.workspace.domain.ProjectAharouRoot
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryRepository @Inject constructor(
    private val globalMemorySource: GlobalMemorySource,
    private val executionModeHolder: ExecutionModeHolder,
    private val containerInstaller: ContainerInstaller,
    private val projectAicodeRoot: ProjectAharouRoot
) {
    /** 按当前会话 projectRoot 创建项目级数据源（内部按执行模式决定存储位置）。 */
    private fun projectSource(projectRoot: String) =
        ProjectMemorySource(projectRoot, executionModeHolder, containerInstaller, projectAicodeRoot)

    /** 扫描并聚合全局和项目级的 memory。同名 memory 项目级优先。 */
    fun listMemories(projectRoot: String?): List<Memory> {
        val allMemories = mutableListOf<Memory>()
        
        // 1. 加载全局记忆
        allMemories.addAll(globalMemorySource.listMemories())
        
        // 2. 加载项目记忆（如果有）
        if (!projectRoot.isNullOrBlank()) {
            allMemories.addAll(projectSource(projectRoot).listMemories())
        }
        
        // 去重：按 name 小写分组，保留最后加入的（即项目级优先覆盖全局级）。
        // 每日日志与月归档（LOG-*）跟记忆条目同在记忆目录里，但它们不是「记忆」：
        // 列进摘要清单只会把清单塞满（空日志全是「无」），正文也已由今日日志尾巴覆盖。
        return allMemories
            .filterNot { it.name.startsWith("LOG-") }
            .groupBy { it.name.lowercase() }
            .map { it.value.last() }
    }

    /**
     * 专供系统提示词注入的清单：在 [listMemories] 基础上做「降级遗忘 + 有界截断」。
     *
     * 与 [listMemories] 分开，是因为后者还供 `memory(action=list)` 用——那里必须给出**完整**视图，
     * 否则截掉的条目模型连名字都看不到，也就无从 `read` 取回，等于真丢了。
     * 注入这条路则相反：只增不减的记忆每轮都挤占上下文，必须会忘。
     *
     * 遗忘只降级不删除：文件原样留着，模型需要时仍能 read/edit/delete。
     * 排序完全由文件 mtime + name 决定，与目录扫描顺序无关，保证同一状态每次注入的集合一致。
     */
    fun listMemoriesForPrompt(projectRoot: String?): List<Memory> = listMemories(projectRoot)
        .filterNot { isStale(it) }
        .sortedWith(
            compareByDescending<Memory> { it.file?.lastModified() ?: 0L }
                .thenBy { it.name.lowercase() }
        )
        .take(MAX_INJECTED_MEMORIES)

    /**
     * 判定一条记忆是否已「遗忘」（长期未改动）。
     *
     * 核心档案（名字含 CORE / GLOBAL）永远保留——它们承载的正是「长期不变的约定」，
     * 长期不动是正常状态，被降级等于用户丢了自己的全局偏好。
     * 取不到文件或时间戳时保守保留，宁可多注入也不误伤。
     */
    private fun isStale(memory: Memory): Boolean {
        if (isCoreArchive(memory.name)) return false
        val lastModified = memory.file?.lastModified() ?: return false
        if (lastModified <= 0L) return false
        return System.currentTimeMillis() - lastModified > STALE_MEMORY_MILLIS
    }

    /** 核心档案：名字含 CORE / GLOBAL 的条目（覆盖 CORE.md、GLOBAL.md 及其派生名）。 */
    private fun isCoreArchive(name: String): Boolean {
        val upper = name.uppercase()
        return upper.contains("CORE") || upper.contains("GLOBAL")
    }

    private companion object {
        /** 注入清单的条数上限。超出的条目只是不进提示词，文件仍在，模型仍可 read / edit / delete。 */
        const val MAX_INJECTED_MEMORIES = 60

        /** 遗忘阈值（天）：超过这么久没改动的普通条目不再注入。 */
        const val STALE_MEMORY_DAYS = 180L

        const val STALE_MEMORY_MILLIS = STALE_MEMORY_DAYS * 24L * 60L * 60L * 1000L
    }

    /** 读取指定 memory 的完整指令正文；不存在 / 解析失败返回 null。 */
    fun loadContent(name: String, projectRoot: String?): String? {
        // 优先从项目级读取
        if (!projectRoot.isNullOrBlank()) {
            val content = projectSource(projectRoot).loadContent(name)
            if (content != null) return content
        }
        // 回退到全局读取
        return globalMemorySource.loadContent(name)
    }

    fun saveMemory(name: String, description: String, content: String, scope: MemoryScope, projectRoot: String?): Boolean {
        return when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.saveMemory(name, description, content)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) false
                else projectSource(projectRoot).saveMemory(name, description, content)
            }
        }
    }

    fun editMemory(name: String, edits: List<MemoryEdit>, scope: MemoryScope, projectRoot: String?): MemoryEditResult {
        return when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.editMemory(name, edits)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) MemoryEditResult.Error("NO_WORKSPACE", "当前未选择工作区，无法编辑项目级记忆")
                else projectSource(projectRoot).editMemory(name, edits)
            }
        }
    }

    fun deleteMemory(name: String, scope: MemoryScope, projectRoot: String?): Boolean {
        return when (scope) {
            MemoryScope.GLOBAL -> globalMemorySource.deleteMemory(name)
            MemoryScope.PROJECT -> {
                if (projectRoot.isNullOrBlank()) false
                else projectSource(projectRoot).deleteMemory(name)
            }
        }
    }
}
