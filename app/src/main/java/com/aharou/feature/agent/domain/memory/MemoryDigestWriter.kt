package com.aharou.feature.agent.domain.memory

import com.aharou.core.util.FileLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 上下文压缩摘要的落盘入口。
 *
 * 压缩摘要原先只进消息库：一旦会话又把这份摘要压进下一份，再往前的原始细节就彻底找不回来了。
 * 落成一条 digest 记忆留档后，模型仍能用 memory(search/read) 把被压掉的那段历史取出来；
 * 而 digest 不进注入清单，所以它不会反过来挤占上下文——「可检索、不常驻」就是这一档的意义。
 *
 * **调用时机**：摘要校验通过、会话历史原子替换之后（`ContextCompactor.commitPreparation`
 * 里 `agentMessageDao.commitCompaction` 成功之后），每次成功压缩写一条。
 * 压缩本身不能因为这次写入失败而失败：摘要已经进了消息库，这里只是留档，失败记日志即可。
 */
@Singleton
class MemoryDigestWriter @Inject constructor(
    private val memoryRepository: MemoryRepository
) {

    companion object {
        private const val TAG = "MemoryDigestWriter"

        /** 名字前缀：与「蒸馏-」同一套约定，翻目录时一眼看出这是自动产出的留档。 */
        const val NAME_PREFIX = "摘要-"

        /** 同一秒内同一会话连压两次会重名，往后加序号重试；再撞就放弃，不无限试。 */
        private const val MAX_NAME_ATTEMPTS = 5

        /** 摘要栏上限：它要出现在 search / list 的结果行里，太长会把结果列表撑爆。 */
        private const val MAX_DESCRIPTION_CHARS = 120
    }

    /**
     * 把一份压缩摘要写成 digest 记忆。
     *
     * @param summary 压缩产出的 handoff summary 正文（原样留档，不再二次加工）
     * @param sessionId 会话 id：只用来给文件名加一段可回溯的标识，不参与任何逻辑判定
     * @param scope 记忆作用域，默认 GLOBAL——会话压缩属于某段对话，不绑定工作区；
     *   传 PROJECT 时必须给 [projectRoot]，否则没有可写的目录（与项目记忆保存同一口径）
     * @param projectRoot 当前工作区根路径，`scope=PROJECT` 时必填
     * @return 落盘后的条目；摘要为空、缺工作区或写盘失败时返回 null
     */
    fun writeDigest(
        summary: String,
        sessionId: String? = null,
        scope: MemoryScope = MemoryScope.GLOBAL,
        projectRoot: String? = null
    ): Memory? {
        val content = summary.trim()
        if (content.isEmpty()) return null
        if (scope == MemoryScope.PROJECT && projectRoot.isNullOrBlank()) {
            FileLogger.w(TAG, "上下文摘要未写入：项目级记忆没有当前工作区")
            return null
        }

        val base = nameOf(sessionId)
        repeat(MAX_NAME_ATTEMPTS) { attempt ->
            val name = if (attempt == 0) base else "$base-${attempt + 1}"
            // 用「要求文件不存在」的版本语义抢名字：两次压缩撞在同一秒时后一次拿到冲突、换序号重试，
            // 而不是覆盖前一次——摘要一旦被顶掉就没有第二份可回滚。
            val result = memoryRepository.writeMemory(
                name = name,
                description = describe(content),
                content = content,
                scope = scope,
                projectRoot = projectRoot,
                type = MemoryType.DIGEST,
                expectedVersion = MemorySource.VERSION_NEW
            )
            when (result) {
                MemoryWriteResult.Success -> return memoryRepository.findMemory(name, projectRoot, scope)
                is MemoryWriteResult.VersionConflict -> Unit
                is MemoryWriteResult.Failure -> {
                    FileLogger.w(TAG, "上下文摘要留档失败：${result.code}：${result.message}")
                    return null
                }
            }
        }
        FileLogger.w(TAG, "上下文摘要留档放弃：同名条目已存在（$base）")
        return null
    }

    /** 名字 = 前缀 + 时间戳 +（可选）会话标识；时间戳保证每次压缩各留一条，不互相顶掉。 */
    private fun nameOf(sessionId: String?): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val session = sessionId?.trim()?.takeIf { it.isNotEmpty() }?.take(8)
        val raw = if (session == null) NAME_PREFIX + stamp else NAME_PREFIX + stamp + "-" + session
        return MemorySource.sanitizeName(raw)
    }

    /** 摘要栏取正文首行：检索结果行要能一眼看出这是哪段会话留下来的。 */
    private fun describe(content: String): String {
        val line = content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: return "上下文压缩摘要"
        return if (line.length <= MAX_DESCRIPTION_CHARS) line else line.take(MAX_DESCRIPTION_CHARS) + "…"
    }
}
