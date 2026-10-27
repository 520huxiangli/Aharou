package com.aharou.feature.agent.domain.memory

import com.aharou.core.memory.AharouMemoryStore
import com.aharou.core.util.FileLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把蒸馏要点落成普通记忆条目。
 *
 * 以前蒸馏结果是一整段 Markdown 追加进 GLOBAL.md：能读不能用——检索搜不到、改不了单条、
 * 每轮还要把整段回喂给模型。落成条目后，检索 / `memory read|edit|delete` / 统计 / 撤销
 * 全部沿用现有机制，这里只负责「名字怎么定」和「条目不能无限涨」两件事。
 */
@Singleton
class DistilledMemoryWriter @Inject constructor(
    private val memoryRepository: MemoryRepository,
    private val memoryStore: AharouMemoryStore,
) {

    companion object {
        private const val TAG = "DistilledMemoryWriter"

        /** 蒸馏条目保留上限；超出按修改时间归档最旧的，避免长期累积挤占注入预算。 */
        const val MAX_ENTRIES = 60
    }

    /**
     * 写入一批要点，返回真正落盘的条数。
     *
     * 名字由正文开头生成，模型换个说法就会生成新名字——所以写入前先用相似度找一遍，
     * 命中就把内容合并进已有条目（保留原名），否则才新建。
     */
    fun write(entries: List<DistilledEntry>, projectRoot: String? = null): Int {
        var written = 0
        for (entry in entries) {
            // 先找同名条目（重复蒸馏会命中），再退到相似条目：两者都是「要改的已有条目」，
            // 带上它当前的版本号，才不至于把蒸馏期间被别处改过的正文盖掉。
            val target = memoryRepository.findMemory(entry.name, projectRoot, MemoryScope.GLOBAL)
                ?: memoryRepository.findSimilarMemories(
                    name = entry.name,
                    description = entry.description,
                    projectRoot = projectRoot,
                    limit = 1,
                    scope = MemoryScope.GLOBAL,
                ).firstOrNull()
            val targetName = target?.name ?: entry.name
            val result = memoryRepository.writeMemory(
                name = targetName,
                description = entry.description,
                content = entry.content,
                scope = MemoryScope.GLOBAL,
                projectRoot = projectRoot,
                type = entry.type,
                pending = entry.pending,
                keywords = entry.keywords,
                expectedVersion = target?.version ?: MemorySource.VERSION_NEW,
            )
            if (result == MemoryWriteResult.Success) {
                written++
            } else {
                FileLogger.w(TAG, "蒸馏条目「$targetName」未写入：${result.failureSummary()}")
            }
        }
        trimOverflow()
        return written
    }

    /**
     * 拼上一轮要点，供蒸馏做增量更新。
     *
     * 回喂旧要点而不是每轮从零重写：重写会一轮轮洗掉细节，最终约定与偏好跟流水一起被稀释。
     */
    fun previousEntriesText(maxChars: Int): String? {
        val entries = memoryRepository
            .listMemories(projectRoot = null, scope = MemoryScope.GLOBAL)
            .filter { it.name.startsWith(DistilledEntryParser.NAME_PREFIX) }
        if (entries.isEmpty()) return null
        val text = entries.joinToString("\n") { entry ->
            val keywords = if (entry.keywords.isEmpty()) "" else "{关键词: ${entry.keywords.joinToString("，")}}"
            "- [${entry.type.key}] ${entry.content}$keywords"
        }
        return text.take(maxChars).takeIf { it.isNotBlank() }
    }

    /** 裁剪超额条目：删之前由 deleteMemory 自动归档，undo 还能拉回来。 */
    private fun trimOverflow() {
        val root = memoryStore.dir
        val files = root.listFiles { file ->
            file.isFile && file.extension == "md" && file.name.startsWith(DistilledEntryParser.NAME_PREFIX)
        }?.sortedByDescending { it.lastModified() } ?: return
        if (files.size <= MAX_ENTRIES) return
        files.drop(MAX_ENTRIES).forEach { file ->
            runCatching {
                memoryRepository.deleteMemory(file.nameWithoutExtension, MemoryScope.GLOBAL, null)
            }.onFailure { FileLogger.w(TAG, "裁剪蒸馏条目失败: ${file.name}", it) }
        }
    }
}
