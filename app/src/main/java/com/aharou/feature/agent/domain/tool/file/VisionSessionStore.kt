package com.aharou.feature.agent.domain.tool.file

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.model.AgentMessage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 识图会话的落盘存储：每个会话一个 JSON 文件，内容为发给识图模型的完整消息历史（含图片 base64），
 * 目录 `~/.aicode/vision-sessions/`，风格同 ToolOutputStore。会话数超上限时按最后修改时间淘汰最久未用的。
 */
@Singleton
class VisionSessionStore @Inject constructor(
    private val containerInstaller: ContainerInstaller
) {
    private companion object {
        const val TAG = "VisionSessionStore"
        const val SESSION_DIR = "vision-sessions"
        const val MAX_SESSIONS = 100
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()

    /** 会话文件目录（宿主路径）。对外只用于占用统计与清理。 */
    val sessionDir: File get() = File(containerInstaller.aicodeDir, SESSION_DIR)

    private fun fileFor(id: String): File = File(sessionDir, "vision-$id.json")

    /** 读取会话历史；会话不存在或文件损坏返回 null。 */
    suspend fun load(id: String): List<AgentMessage>? = mutex.withLock {
        val file = fileFor(id)
        if (!file.isFile) return null
        runCatching { json.decodeFromString<List<AgentMessage>>(file.readText()) }
            .onFailure { FileLogger.w(TAG, "读取识图会话 $id 失败: ${it.message}") }
            .getOrNull()
    }

    /** 覆盖写入会话历史并触发淘汰检查。 */
    suspend fun save(id: String, messages: List<AgentMessage>) = mutex.withLock {
        val file = fileFor(id)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(messages))
        evictIfNeeded()
    }

    /** 删除会话（首次识图失败时清理，避免留下孤儿文件）。 */
    suspend fun delete(id: String) = mutex.withLock {
        fileFor(id).delete()
    }

    private fun evictIfNeeded() {
        val dir = sessionDir
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("vision-") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        if (files.size <= MAX_SESSIONS) return
        files.drop(MAX_SESSIONS).forEach { old ->
            FileLogger.i(TAG, "淘汰过期识图会话: ${old.name}")
            old.delete()
        }
    }
}
