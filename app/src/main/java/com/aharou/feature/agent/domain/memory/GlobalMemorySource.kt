package com.aharou.feature.agent.domain.memory

import com.aharou.core.memory.AharouMemoryStore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GlobalMemorySource @Inject constructor(
    private val memoryStore: AharouMemoryStore
) : MemorySource {

    private val memoryRoot: File get() = memoryStore.dir.also { it.mkdirs() }

    /** 这里不用 [memoryRoot]（它会 mkdirs）：UI 只是看看目录在不在，不该因为列一次归档就把目录建出来。 */
    override val root: File? get() = memoryStore.dir

    override fun listMemories(): List<Memory> {
        if (!memoryRoot.exists()) return emptyList()
        val files = memoryRoot.listFiles { file -> file.isFile && file.extension == "md" } ?: return emptyList()
        
        return files.mapNotNull { file -> MemoryParser.parse(file, MemoryScope.GLOBAL) }
            .sortedBy { it.name.lowercase() }
    }

    override fun loadContent(name: String): String? {
        return listMemories()
            .firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?.content
    }

    override fun saveMemory(
        name: String, description: String, content: String, type: MemoryType?,
        expiresAt: Long?, pending: Boolean?, keywords: List<String>?
    ): Boolean = MemoryFileStorage.save(
        memoryRoot, MemoryScope.GLOBAL, name, description, content, type, expiresAt, pending, keywords
    )

    override fun archiveContent(
        name: String, description: String, content: String, type: MemoryType?,
        keywords: List<String>, expiresAt: Long?, pending: Boolean
    ): Boolean = MemorySource.writeArchiveFile(
        memoryRoot, name, description, content, "GlobalMemorySource", type, keywords, expiresAt, pending
    ) != null

    override fun deleteMemory(name: String): Boolean = synchronized(MemoryFileStorage.lock) {
        val file = MemorySource.resolveMemoryFile(memoryRoot, name)
        if (!file.isFile) return@synchronized false
        // 删除是全链路唯一没有回滚路径的写操作：先留一份归档，误删还能用 memory(action=undo) 拉回。
        MemorySource.archiveExistingFile(memoryRoot, name)
        file.delete()
    }
}
