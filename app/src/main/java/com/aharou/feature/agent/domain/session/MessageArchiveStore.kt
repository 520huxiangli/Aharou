package com.aharou.feature.agent.domain.session

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageArchiveStore internal constructor(private val root: File) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, "message-archive"))

    private val mutex = Mutex()

    suspend fun prepareForStorage(entity: AgentMessageEntity): AgentMessageEntity = withContext(Dispatchers.IO) {
        mutex.withLock {
            transform(entity) { field, value, maxBytes, text ->
                val existing = value?.let { reference(it, field) }
                if (value == null) null
                else {
                    val preview = if (text) MessagePersistenceUseCase.sanitizeContent(value)
                    else MessagePersistenceUseCase.capBytes(value, maxBytes)
                    if (preview == value && existing == null) value
                    else {
                        currentCoroutineContext().ensureActive()
                        val bytes = value.toByteArray(Charsets.UTF_8)
                        val digest = hash(bytes)
                        val target = archiveFile(entity, field, digest)
                        writeAtomically(target, bytes)
                        val marker = "\n[aharou-message-archive:v1:$field:$digest]"
                        val bounded = if (text) MessagePersistenceUseCase.capBytes(preview, maxBytes - marker.toByteArray(Charsets.UTF_8).size)
                        else ""
                        bounded + marker
                    }
                }
            }
        }
    }

    suspend fun restore(entity: AgentMessageEntity): AgentMessageEntity = withContext(Dispatchers.IO) {
        mutex.withLock {
            transform(entity) { field, value, _, text ->
                val match = value?.let { reference(it, field) }
                if (match == null) value
                else restoreWhole(entity, field, value, text, match)
            }
        }
    }

    /**
     * 历史回放用的有界恢复：单个字段最多读回 [maxBytes] 字节，超出部分丢弃并打截断标记。
     *
     * 回放链路的下一步是 JSON 解析与上下文拼装，整份读回没有意义——一个长会话几千条消息时，
     * 全量恢复等于把档案里所有大字段同时物化到堆上，而回放实际只用得上前几段。
     * 档案文件本身保持完整：界面展示、备份导出、复制会话仍走 [restore] 读全量。
     * 签名例外：[FIELD_SIGNATURE] 走全量——Anthropic thinking 的签名改一个字节就会 400，截断等于报废。
     */
    suspend fun restoreBounded(entity: AgentMessageEntity, maxBytes: Int): AgentMessageEntity = withContext(Dispatchers.IO) {
        mutex.withLock {
            transform(entity) { field, value, _, text ->
                val match = value?.let { reference(it, field) }
                if (match == null) value
                else if (field == FIELD_SIGNATURE) restoreWhole(entity, field, value, text, match)
                else restoreHead(entity, field, value, text, match, maxBytes)
            }
        }
    }

    private suspend fun restoreWhole(
        entity: AgentMessageEntity,
        field: String,
        value: String?,
        text: Boolean,
        match: MatchResult
    ): String? = try {
        val file = archiveFile(entity, field, match.groupValues[2])
        readChecked(file, match.groupValues[2]).toString(Charsets.UTF_8)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        unavailable(entity, field, value, text, match)
    }

    private suspend fun restoreHead(
        entity: AgentMessageEntity,
        field: String,
        value: String?,
        text: Boolean,
        match: MatchResult,
        maxBytes: Int
    ): String? = try {
        readHead(archiveFile(entity, field, match.groupValues[2]), maxBytes)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        unavailable(entity, field, value, text, match)
    }

    /** 档案读不回来时的兜底：文本字段保留库里的预览（标记之前那段），其余字段当没存过。 */
    private fun unavailable(
        entity: AgentMessageEntity,
        field: String,
        value: String?,
        text: Boolean,
        match: MatchResult
    ): String? {
        FileLogger.w(TAG, "Archive unavailable for field=$field message=${hash(entity.id.toByteArray())}")
        return if (text) (value ?: "").substring(0, match.range.first) + UNAVAILABLE_MARKER else null
    }

    /**
     * 只读档案文件的前 [maxBytes] 字节。整份校验和在这里不成立（摘要针对全量），只保证文件存在；
     * 末尾正好落在半个多字节字符上时丢掉那段残字符，避免回放里出现替换符。
     */
    private suspend fun readHead(file: File, maxBytes: Int): String {
        if (!file.isFile) throw IOException("Message archive missing")
        val buffer = ByteArray(maxBytes.coerceAtLeast(0))
        var read = 0
        file.inputStream().use { input ->
            while (read < buffer.size) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer, read, buffer.size - read)
                if (count < 0) break
                read += count
            }
        }
        currentCoroutineContext().ensureActive()
        var head = String(buffer, 0, read, Charsets.UTF_8)
        while (head.isNotEmpty() && head.last() == '\uFFFD') head = head.dropLast(1)
        val total = file.length()
        if (total <= read) return head
        return head + TRUNCATED_MARKER_PREFIX + "${head.length} chars kept, ${total - read} bytes omitted]"
    }

    suspend fun restoreContentForSearch(entity: AgentMessageEntity): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val match = reference(entity.content, "content")
            if (match == null) entity.content
            else {
                try {
                    readChecked(archiveFile(entity, "content", match.groupValues[2]), match.groupValues[2])
                        .toString(Charsets.UTF_8)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    entity.content.substring(0, match.range.first)
                }
            }
        }
    }

    suspend fun restoreAll(entities: List<AgentMessageEntity>): List<AgentMessageEntity> =
        entities.map { restore(it) }

    suspend fun deleteMessage(sessionId: String, messageId: String) = withContext(Dispatchers.IO) {
        mutex.withLock { deleteDirectory(messageDirectory(sessionId, messageId)) }
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        mutex.withLock { deleteDirectory(safeChild(root, hash(sessionId.toByteArray()))) }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock { deleteDirectory(root) }
    }

    private suspend fun transform(
        entity: AgentMessageEntity,
        field: suspend (String, String?, Int, Boolean) -> String?
    ): AgentMessageEntity = entity.copy(
        content = field("content", entity.content, MessagePersistenceUseCase.MAX_CONTENT_BYTES, true).orEmpty(),
        reasoning = field("reasoning", entity.reasoning, MessagePersistenceUseCase.MAX_CONTENT_BYTES, true),
        toolCallsJson = field("toolCallsJson", entity.toolCallsJson, MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES, false),
        toolArgs = field("toolArgs", entity.toolArgs, MessagePersistenceUseCase.MAX_TOOL_ARGS_BYTES, false),
        signature = field("signature", entity.signature, MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES, false),
        thinkingBlocksJson = field("thinkingBlocksJson", entity.thinkingBlocksJson, MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES, false),
        attachmentsJson = field("attachmentsJson", entity.attachmentsJson, MessagePersistenceUseCase.MAX_ATTACHMENTS_BYTES, false)
    )

    private fun reference(value: String, field: String): MatchResult? =
        REFERENCE.find(value)?.takeIf { it.groupValues[1] == field }

    private fun messageDirectory(sessionId: String, messageId: String): File =
        safeChild(safeChild(root, hash(sessionId.toByteArray())), hash(messageId.toByteArray()))

    private fun archiveFile(entity: AgentMessageEntity, field: String, digest: String): File =
        safeChild(messageDirectory(entity.sessionId, entity.id), "$field-$digest.txt")

    private fun safeChild(parent: File, name: String): File {
        val child = File(parent, name)
        if (child.canonicalFile.parentFile != parent.canonicalFile) throw IOException("Invalid archive path")
        return child
    }

    private suspend fun writeAtomically(target: File, bytes: ByteArray) {
        val directory = requireNotNull(target.parentFile)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create message archive")
        val temporary = File.createTempFile("archive-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                var offset = 0
                while (offset < bytes.size) {
                    currentCoroutineContext().ensureActive()
                    val count = minOf(IO_CHUNK, bytes.size - offset)
                    output.write(bytes, offset, count)
                    offset += count
                }
                output.fd.sync()
            }
            currentCoroutineContext().ensureActive()
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    private suspend fun readChecked(file: File, expected: String): ByteArray {
        if (!file.isFile) throw IOException("Message archive missing")
        val digest = MessageDigest.getInstance("SHA-256")
        val output = java.io.ByteArrayOutputStream()
        file.inputStream().use { input ->
            val buffer = ByteArray(IO_CHUNK)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
        }
        currentCoroutineContext().ensureActive()
        if (hex(digest.digest()) != expected) throw IOException("Message archive checksum mismatch")
        return output.toByteArray()
    }

    private suspend fun deleteDirectory(directory: File) {
        if (!directory.exists()) return
        if (!directory.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
            throw IOException("Invalid archive deletion path")
        }
        directory.listFiles()?.forEach { child ->
            currentCoroutineContext().ensureActive()
            if (child.isDirectory) deleteDirectory(child)
            else if (!child.delete()) throw IOException("Cannot delete message archive")
        }
        if (!directory.delete()) throw IOException("Cannot delete message archive directory")
    }

    private fun hash(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val TAG = "MessageArchiveStore"
        private const val IO_CHUNK = 64 * 1024
        internal const val UNAVAILABLE_MARKER = "\n[message archive unavailable]"
        private const val TRUNCATED_MARKER_PREFIX = "\n[truncated: "
        private const val FIELD_SIGNATURE = "signature"
        private val REFERENCE = Regex("\\n\\[aharou-message-archive:v1:([a-zA-Z]+):([0-9a-f]{64})]$")
    }
}
