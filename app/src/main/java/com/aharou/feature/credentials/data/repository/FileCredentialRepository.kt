package com.aharou.feature.credentials.data.repository

import android.content.Context
import android.util.Base64
import com.aharou.core.util.FileLogger
import com.aharou.core.util.writeTextSafely
import com.aharou.feature.credentials.domain.model.GitCredential
import com.aharou.feature.credentials.domain.repository.CredentialRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * git 凭据的文件实现（真源 = `filesDir/aharou/git-credentials`，容器内 `/root/.aharou/git-credentials`）。
 *
 * 文件采用 git-credential-store 标准格式：每行 `https://user:token@host`，每主机一条（保存同主机覆盖旧值），
 * 由容器/远程服务器的 `credential.helper=store` 直接读取，UI 与 git 共用同一份。
 * 内存以 StateFlow 缓存，写操作（save/delete）原子落盘后刷新。
 *
 * **读失败一律不写回**：写操作是「内存列表整体重写」，一旦文件整份读不到（被截断、编码损坏、IO 异常），
 * 内存里只剩空表，继续写就会把其余主机的凭据静默抹掉。因此读失败时置只读标记、另存一份原文件，
 * 并让 save/delete 直接失败（见 [CredentialStorageReadOnlyException]）。
 */
@Singleton
class FileCredentialRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) : CredentialRepository {

    private companion object {
        const val TAG = "FileCredentialRepo"
        const val AHAROU_DIR = "aharou"

        /** [Aharou 改名] 旧目录名：老版本把凭据写在这里，需要一次性合并。 */
        const val LEGACY_DIR = "aicode"
        const val CREDENTIALS_NAME = "git-credentials"
    }

    private val credentialsFile: File get() = File(File(context.filesDir, AHAROU_DIR), CREDENTIALS_NAME)

    private val mutex = Mutex()

    private val loaded = loadFromDisk()

    /** 只读原因（null = 正常）；只读期间禁止任何落盘。 */
    private val readFailure: ReadFailure? = loaded.failure

    private val _credentials = MutableStateFlow(loaded.credentials)
    override fun getAll(): Flow<List<GitCredential>> = _credentials

    /** 读盘只发生在构造时，之后不会恢复，故不需要可变的 StateFlow。 */
    override val storageReadOnly: StateFlow<Boolean> = MutableStateFlow(loaded.failure != null)

    override suspend fun findForHost(host: String): GitCredential? {
        val normalized = host.trim().lowercase()
        if (normalized.isEmpty()) return null
        return _credentials.value.firstOrNull { it.host == normalized }
    }

    override suspend fun save(credential: GitCredential) = mutex.withLock {
        requireWriteAllowed("保存")
        val host = credential.host.trim().lowercase()
        if (host.isEmpty()) return@withLock
        val updated = _credentials.value
            .filterNot { it.host == host } +
            GitCredential(id = host, host = host, username = credential.username.trim(), token = credential.token)
        writeFile(updated)
        _credentials.value = updated
        FileLogger.i(TAG, "保存凭据 host=$host user=${credential.username}")
    }

    override suspend fun delete(id: String) = mutex.withLock {
        requireWriteAllowed("删除")
        val updated = _credentials.value.filterNot { it.id == id }
        writeFile(updated)
        _credentials.value = updated
        FileLogger.i(TAG, "删除凭据 id=$id")
    }

    /** 读失败时内存只有空表：此时任何落盘都是整体覆盖，直接拒绝而不是安静地写坏文件。 */
    private fun requireWriteAllowed(action: String) {
        val failure = readFailure ?: return
        FileLogger.w(
            TAG,
            "拒绝${action}凭据：凭据文件读取失败（${failure.reason}），只读保护中" +
                if (failure.backupName.isEmpty()) "（备份失败）" else "，原文件备份 ${failure.backupName}"
        )
        throw CredentialStorageReadOnlyException(failure.reason, failure.backupName)
    }

    /** 首次读盘结果：成功时 [credentials] 可能为空（本来就没有凭据），失败时 [failure] 非空。 */
    private class Loaded(
        val credentials: List<GitCredential>,
        val failure: ReadFailure?,
        /** 无法解析而被跳过的行数，供「编码迁移会丢行」的判断使用。 */
        val badLines: Int = 0
    )

    /** 整份读不到的原因；[backupName] 为另存出来的副本名（备份失败时为空串）。 */
    private class ReadFailure(val reason: String, val backupName: String)

    /**
     * 读盘 + 逐行解析。
     *
     * 只有「整份读不到」才返回失败：IO 异常，或文件有内容却一行都解析不出来（被截断 / 编码损坏，
     * 多半是解码失败后落回明文解析）。单行格式异常只跳过该行并记日志，不影响其余主机。
     */
    private fun loadFromDisk(): Loaded {
        val file = credentialsFile
        // 文件不存在 = 还没有凭据，不是读失败
        if (!file.isFile) return Loaded(emptyList(), null)
        val raw = try {
            file.readText()
        } catch (e: Exception) {
            return unreadable("读取文件失败: ${e.message}")
        }
        // 文件内容为「base64 后整体反转」的编码串；解码失败（旧版明文）时按原文处理。
        val plain = tryDecode(raw) ?: raw
        val parsed = mutableListOf<GitCredential>()
        var badLines = 0
        plain.lines().forEachIndexed { index, line ->
            when (val result = parseLine(line)) {
                is LineResult.Ok -> parsed += result.credential
                LineResult.Blank -> Unit
                is LineResult.Bad -> {
                    badLines++
                    FileLogger.w(TAG, "跳过无法解析的凭据行 #${index + 1}（${result.reason}）")
                }
            }
        }
        // 有内容却一条都没解析出来：不能当成「没有凭据」，否则下一次保存就是整体抹除。
        if (parsed.isEmpty() && badLines > 0) return unreadable("整份内容无法解析（$badLines 行）")
        if (badLines > 0) {
            FileLogger.w(TAG, "凭据文件有 $badLines 行无法解析已跳过，其余 ${parsed.size} 条照常加载")
        }
        return Loaded(parsed, null, badLines)
    }

    /** 记只读原因、另存一份原文件，返回带失败标记的读盘结果。 */
    private fun unreadable(reason: String): Loaded {
        val backupName = backupBrokenFile()?.name.orEmpty()
        FileLogger.e(
            TAG,
            "git 凭据读取失败（$reason）：进入只读保护，本次不写回文件" +
                if (backupName.isEmpty()) "（备份失败）" else "，原文件已备份为 $backupName"
        )
        return Loaded(emptyList(), ReadFailure(reason, backupName))
    }

    /** 读失败时把原文件另存为同目录 `git-credentials.broken-<时间戳>`，供用户手工救回。 */
    private fun backupBrokenFile(): File? {
        val src = credentialsFile
        if (!src.isFile) return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dest = File(src.parentFile, "$CREDENTIALS_NAME.broken-$stamp")
        return runCatching {
            src.copyTo(dest, overwrite = false)
            FileLogger.i(TAG, "已备份无法读取的凭据文件: ${dest.name}")
            dest
        }.getOrElse {
            FileLogger.w(TAG, "备份无法读取的凭据文件失败: ${it.message}")
            null
        }
    }

    private sealed interface LineResult {
        data class Ok(val credential: GitCredential) : LineResult
        data object Blank : LineResult
        data class Bad(val reason: String) : LineResult
    }

    /** 解析单行 `https://user:token@host`；坏行只影响自己（见 [loadFromDisk]）。 */
    private fun parseLine(line: String): LineResult {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return LineResult.Blank
        val at = trimmed.lastIndexOf('@')
        if (at < 0) return LineResult.Bad("缺少 @host")
        val host = trimmed.substring(at + 1).trim().lowercase()
        if (host.isEmpty()) return LineResult.Bad("host 为空")
        val rest = trimmed.substring(0, at).substringAfter("://", "")
        val sep = rest.indexOf(':')
        if (sep < 0) return LineResult.Bad("缺少 user:token 分隔符")
        // URLDecoder 遇到残缺的 % 序列会抛异常，必须逐行兜住，否则一行坏数据能毁掉整份解析。
        return runCatching {
            val username = URLDecoder.decode(rest.substring(0, sep), "UTF-8")
            val token = URLDecoder.decode(rest.substring(sep + 1), "UTF-8")
            if (username.isEmpty()) LineResult.Bad("username 为空")
            else LineResult.Ok(GitCredential(id = host, host = host, username = username, token = token))
        }.getOrElse { LineResult.Bad("解码失败: ${it.message}") }
    }

    /** 宽松解析（旧目录迁移用）：读不到就当没有，不参与只读判定、不备份。 */
    private fun parseLeniently(file: File): List<GitCredential> {
        if (!file.isFile) return emptyList()
        val raw = runCatching { file.readText() }.getOrElse {
            FileLogger.w(TAG, "读取 ${file.name} 失败: ${it.message}")
            return emptyList()
        }
        val plain = tryDecode(raw) ?: raw
        return plain.lines().mapNotNull { line -> (parseLine(line) as? LineResult.Ok)?.credential }
    }

    /** 按 git-credentials 格式写文件（先写 .tmp 再 rename，避免 git 读到半截文件）。 */
    private fun writeFile(list: List<GitCredential>) {
        val sb = StringBuilder()
        list.sortedBy { it.host }.forEach { c ->
            sb.append("https://")
                .append(enc(c.username)).append(':').append(enc(c.token))
                .append('@').append(c.host)
                .append('\n')
        }
        credentialsFile.parentFile?.mkdirs()
        val tmp = File(credentialsFile.parentFile, "${credentialsFile.name}.tmp")
        val encoded = encode(sb.toString())
        tmp.writeTextSafely(encoded, TAG)
        if (credentialsFile.exists()) credentialsFile.delete()
        if (!tmp.renameTo(credentialsFile)) {
            credentialsFile.writeTextSafely(encoded, TAG)
            tmp.delete()
        }
    }

    private fun enc(part: String): String = URLEncoder.encode(part, "UTF-8")

    /** 编码：base64 后整体反转，使文件不是可直接回显的明文（属混淆，非加密）。 */
    private fun encode(plain: String): String =
        Base64.encodeToString(plain.toByteArray(Charsets.UTF_8), Base64.NO_WRAP).reversed()

    /**
     * 解码编码串；不是本格式（如旧版明文）时返回 null。
     *
     * android.util.Base64 的 DEFAULT 解码是「宽容」的：`:`、`/`、`@` 等非法字符一律按 SKIP 忽略、
     * 不抛异常，明文也能被「解码」成乱码。因此不能以「未抛异常」判定格式，改用往返校验：只有
     * `encode(decode(raw)) == raw` 才认定为编码格式，否则按明文处理（旧版明文需回退解析，否则凭据全部读空）。
     */
    private fun tryDecode(text: String): String? = runCatching {
        val raw = text.trim()
        if (raw.isEmpty()) return@runCatching null
        val decoded = String(Base64.decode(raw.reversed(), Base64.DEFAULT), Charsets.UTF_8)
        if (encode(decoded) != raw) return@runCatching null
        decoded
    }.getOrNull()

    /** 把旧版明文凭据文件迁移为编码格式（幂等）。 */
    suspend fun migrateToEncoded() = mutex.withLock {
        // 读失败时内存是空表，此处写盘会把文件清空。
        if (readFailure != null) {
            FileLogger.w(TAG, "跳过明文迁移：凭据文件读取失败，只读保护中")
            return@withLock
        }
        if (!credentialsFile.isFile) return@withLock
        val raw = runCatching { credentialsFile.readText() }.getOrNull() ?: return@withLock
        if (tryDecode(raw) != null) return@withLock
        // 有坏行时跳过迁移：重写只会保留解析成功的行，会把坏行一起丢掉。
        if (loaded.badLines > 0) {
            FileLogger.w(TAG, "跳过明文迁移：文件有 ${loaded.badLines} 行无法解析，迁移会丢行")
            return@withLock
        }
        writeFile(_credentials.value)
        FileLogger.i(TAG, "已将明文 git 凭据迁移为编码格式")
    }

    /**
     * 把旧目录 `filesDir/aicode/git-credentials` 并进当前目录（幂等，在 [migrateToEncoded] 之前调）。
     *
     * `ContainerInstaller.migrateLegacyDir` 只在目标目录不存在时才搬，而 `filesDir/aharou`
     * 早被运行时创建，所以旧文件从未被搬过。这里按 host 归并：同名 host 以旧目录那条为准
     * （改名后经 UI 保存的都落在旧目录，是较新的值），旧文件保留不删。
     */
    suspend fun migrateFromLegacyDir() = mutex.withLock {
        if (readFailure != null) {
            FileLogger.w(TAG, "跳过旧目录合并：凭据文件读取失败，只读保护中")
            return@withLock
        }
        val legacyFile = File(File(context.filesDir, LEGACY_DIR), CREDENTIALS_NAME)
        if (!legacyFile.isFile) return@withLock
        val legacy = parseLeniently(legacyFile)
        if (legacy.isEmpty()) return@withLock

        val current = _credentials.value
        val merged = (current.associateBy { it.host } + legacy.associateBy { it.host })
            .values.sortedBy { it.host }
        val unchanged = merged.size == current.size && current.all { c ->
            merged.any { it.host == c.host && it.username == c.username && it.token == c.token }
        }
        if (unchanged) return@withLock
        writeFile(merged)
        _credentials.value = merged
        FileLogger.i(TAG, "已并入旧目录凭据 ${legacy.size} 条（按 host 归并，旧文件保留）")
    }
}
