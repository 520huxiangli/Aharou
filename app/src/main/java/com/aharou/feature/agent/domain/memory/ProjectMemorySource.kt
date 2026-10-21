package com.aharou.feature.agent.domain.memory

import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import com.aharou.feature.workspace.domain.ProjectAharouRoot
import java.io.File

/**
 * 项目级记忆数据源。由 Repository 动态创建（依赖当前会话的 projectRoot）。
 *
 * 本地模式：存 `<projectRoot>/.aicode/memory/`（跟随工作区目录）；
 * 远程模式：projectRoot 是远程服务器路径，java.io.File 无法在本地创建该目录（保存必失败），
 * 故改存全局配置目录下 `memory/projects/<项目名>-<标识哈希>/`（本地可写、跨连接稳定）。
 * 标识哈希基于「IP:端口:路径」计算，不同服务器上的同名路径（如都叫 /home/u/workspace/default）
 * 也会落到不同目录，不会混淆。
 */
class ProjectMemorySource(
    private val projectRoot: String,
    private val executionModeHolder: ExecutionModeHolder,
    private val containerInstaller: ContainerInstaller,
    private val projectAicodeRoot: ProjectAharouRoot
) : MemorySource {

    private val memoryRoot: File by lazy {
        if (executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH) {
            File(File(containerInstaller.aharouDir, "memory/projects"), projectAicodeRoot.projectKey(projectRoot))
        } else {
            File(projectRoot, ".aharou/memory")
        }
    }

    override val root: File? get() = if (projectRoot.isBlank()) null else memoryRoot

    override fun listMemories(): List<Memory> {
        if (projectRoot.isBlank() || !memoryRoot.exists()) return emptyList()
        val files = memoryRoot.listFiles { file -> file.isFile && file.extension == "md" } ?: return emptyList()
        
        return files.mapNotNull { file -> MemoryParser.parse(file, MemoryScope.PROJECT) }
            .sortedBy { it.name.lowercase() }
    }

    override fun loadContent(name: String): String? {
        if (projectRoot.isBlank()) return null
        return listMemories()
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.content
    }

    override fun saveMemory(
        name: String, description: String, content: String, type: MemoryType?,
        expiresAt: Long?, pending: Boolean?, keywords: List<String>?
    ): Boolean {
        if (projectRoot.isBlank()) return false
        return MemoryFileStorage.save(
            memoryRoot, MemoryScope.PROJECT, name, description, content, type, expiresAt, pending, keywords
        )
    }

    override fun archiveContent(
        name: String, description: String, content: String, type: MemoryType?,
        keywords: List<String>, expiresAt: Long?, pending: Boolean
    ): Boolean = root?.let {
        MemorySource.writeArchiveFile(it, name, description, content, "ProjectMemorySource", type, keywords, expiresAt, pending)
    } != null

    override fun deleteMemory(name: String): Boolean = synchronized(MemoryFileStorage.lock) {
        val dir = root ?: return@synchronized false
        val file = MemorySource.resolveMemoryFile(dir, name)
        if (!file.isFile) return@synchronized false
        // 删除是全链路唯一没有回滚路径的写操作：先留一份归档，误删还能用 memory(action=undo) 拉回。
        MemorySource.archiveExistingFile(dir, name)
        file.delete()
    }
}
