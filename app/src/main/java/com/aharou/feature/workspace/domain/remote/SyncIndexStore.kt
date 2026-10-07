package com.aharou.feature.workspace.domain.remote

import com.aharou.core.util.FileLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 单个文件上次成功同步后的指纹。索引按挂载持久化，用于「跳过未变文件」——远程 SFTP/FTP
 * 拿不到远端内容哈希，故只能靠本地记录的指纹做比对（rsync quick-check 思路）。
 */
@Serializable
data class FileFingerprint(
    val size: Long,
    val mtimeMs: Long,
    /** 内容 SHA-256（十六进制小写）；为空表示尚未计算（仅同尺寸改写时才补算，避免每次上传都全量读盘）。 */
    val sha256: String? = null
)

/**
 * 索引所属的远端身份：同一挂载换到别的远端目录/连接后，旧指纹不再代表当前远端内容。
 * 只按本地相对路径做 key 会让新远端误判「未变」而漏传，故索引必须绑定远端身份。
 */
@Serializable
data class SyncIndexIdentity(
    val connectionId: String,
    val host: String,
    val remotePath: String
)

/** 索引文件的落盘结构：远端身份 + 指纹表（身份不匹配时整表作废）。 */
@Serializable
private data class SyncIndexFile(
    val identity: SyncIndexIdentity? = null,
    val fingerprints: Map<String, FileFingerprint> = emptyMap()
)

/**
 * 同步指纹索引的持久化：每个挂载一个 JSON 文件（`<dir>/<mountId>.json`），key 为相对挂载根的路径。
 *
 * 用相对路径而非绝对路径作 key，挂载的本地路径被修改后索引仍可复用。写入走「临时文件 + rename」
 * 原子落盘，避免写一半崩溃损坏索引；读取失败按空索引处理，退化为全量上传而不是漏传。
 */
class SyncIndexStore(private val dir: File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // 同一挂载的批处理协程与手动上传协程可能并发写同一文件，串行化避免交错产出坏 JSON。
    private val saveLock = Any()

    private fun fileOf(mountId: String): File = File(dir, "$mountId.json")

    /**
     * 读取索引；文件不存在、损坏或 [identity] 与记录不符时返回空（视为全部需要上传，保守）。
     * 身份不符含旧格式（无身份）索引，一律作废重传而不是复用可能指向别处的指纹。
     */
    fun load(mountId: String, identity: SyncIndexIdentity): Map<String, FileFingerprint> {
        val file = fileOf(mountId)
        if (!file.isFile) return emptyMap()
        return runCatching {
            val index = json.decodeFromString<SyncIndexFile>(file.readText())
            if (index.identity != identity) emptyMap() else index.fingerprints
        }.getOrElse { e ->
            FileLogger.w(TAG, "读取同步索引失败，按空索引处理（将全量上传）: ${file.name}: ${e.message}")
            emptyMap()
        }
    }

    fun save(mountId: String, identity: SyncIndexIdentity, fingerprints: Map<String, FileFingerprint>) {
        synchronized(saveLock) {
            runCatching {
                dir.mkdirs()
                val payload = json.encodeToString(SyncIndexFile(identity, fingerprints))
                // 临时名唯一化：并发写同一挂载时互不覆盖，rename 才原子生效
                val tmp = File.createTempFile("$mountId-", ".json.tmp", dir)
                tmp.writeText(payload)
                val target = fileOf(mountId)
                if (!tmp.renameTo(target)) {
                    // rename 失败（罕见）回退直接写，避免丢索引
                    target.writeText(payload)
                    tmp.delete()
                }
            }.onFailure { FileLogger.w(TAG, "保存同步索引失败: $mountId", it) }
        }
    }

    /** 删除某挂载的索引（「清除同步记录」）；下次上传将视为全部未同步。 */
    fun clear(mountId: String) {
        runCatching { fileOf(mountId).delete() }
    }

    private companion object {
        const val TAG = "SyncIndexStore"
    }
}

/** 单文件是否需要上传的纯判定逻辑（与网络/磁盘副作用解耦，便于单测）。 */
object SyncDecision {

    enum class Action { UPLOAD, SKIP_UNCHANGED }

    /**
     * 决定是否上传。**保守原则：任何不确定都判 [Action.UPLOAD]**，绝不误跳过导致远端缺文件。
     *
     * @param stored 上次成功同步的指纹；null 表示无记录
     * @param size 当前本地文件大小
     * @param mtime 当前本地文件修改时间（ms）
     * @param currentSha 惰性计算当前文件 SHA-256 的函数；仅在「size 相同但 mtime 不同」时需要时调用
     */
    fun decide(
        stored: FileFingerprint?,
        size: Long,
        mtime: Long,
        currentSha: () -> String?
    ): Action {
        if (stored == null) return Action.UPLOAD
        if (stored.size != size) return Action.UPLOAD
        // size+mtime 都一致：rsync quick-check 命中，跳过（不读文件内容）
        if (stored.mtimeMs == mtime) return Action.SKIP_UNCHANGED
        // size 相同、mtime 不同：可能是「改了又改回来」或同年份精度内的改动，用内容哈希兜底
        val oldSha = stored.sha256 ?: return Action.UPLOAD
        val newSha = currentSha() ?: return Action.UPLOAD
        return if (oldSha == newSha) Action.SKIP_UNCHANGED else Action.UPLOAD
    }
}
