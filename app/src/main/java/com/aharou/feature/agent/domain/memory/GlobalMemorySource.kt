package com.aharou.feature.agent.domain.memory

import com.aharou.core.memory.AharouMemoryStore
import com.aharou.core.util.FileLogger
import com.aharou.core.util.writeTextSafely
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GlobalMemorySource @Inject constructor(
    private val memoryStore: AharouMemoryStore
) : MemorySource {

    private val memoryRoot: File get() = memoryStore.dir.also { it.mkdirs() }

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

    override fun saveMemory(name: String, description: String, content: String): Boolean {
        return try {
            if (!memoryRoot.exists()) memoryRoot.mkdirs()
            val file = MemorySource.resolveMemoryFile(memoryRoot, name)
            val payload = MemoryParser.format(MemorySource.sanitizeName(name), description, content)
            // 内容一字未变就不重写：mtime 既参与注入排序，又是「长期未改动就静默遗忘」的判据，
            // 无谓刷新会让陈旧条目永远显得新鲜，也会白白抖动 KV Cache。
            if (file.isFile && file.readText() == payload) return true
            // 覆盖前先留一份旧版：旧值往往是判断事实演变方向的唯一证据。
            MemorySource.archiveExistingFile(memoryRoot, name)
            file.writeTextSafely(payload, "GlobalMemorySource")
            true
        } catch (e: Exception) {
            FileLogger.e("GlobalMemorySource", "Failed to save memory: $name", e)
            false
        }
    }

    override fun archiveContent(name: String, description: String, content: String): Boolean =
        MemorySource.writeArchiveFile(memoryRoot, name, description, content, "GlobalMemorySource") != null

    override fun deleteMemory(name: String): Boolean {
        val file = MemorySource.resolveMemoryFile(memoryRoot, name)
        return if (file.exists()) file.delete() else false
    }
}
