package com.aharou.feature.agent.domain.workflow

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.memory.MemoryDigestWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 压缩收尾：记一条完成日志，并把摘要另存一档记忆。
 *
 * 摘要不进注入清单（模型需要时自己用 memory 翻回来）；写记忆失败只记日志，不拖垮压缩本身
 * ——压下去才是这一轮的目的，记忆是附加产物。
 *
 * 单独成文件是因为 [ContextCompactor] 已贴到单文件行数上限，收尾逻辑再往里塞就会越线。
 */
internal suspend fun recordCompactionDone(
    writer: MemoryDigestWriter,
    blockCount: Int,
    tokens: Int,
    summary: String,
    sessionId: String?
) {
    FileLogger.i("ContextCompactor", "上下文压缩完成：$blockCount 块，压缩后 $tokens tokens")
    if (sessionId == null) return
    runCatching { withContext(Dispatchers.IO) { writer.writeDigest(summary, sessionId) } }
        .onFailure { FileLogger.w("ContextCompactor", "压缩摘要写入记忆失败：${it.message}") }
}
