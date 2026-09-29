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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * git 凭据的文件实现（真源 = `filesDir/aharou/git-credentials`，容器内 `/root/.aharou/git-credentials`）。
 *
 * 文件采用 git-credential-store 标准格式：每行 `https://user:token@host`，每主机一条（保存同主机覆盖旧值），
 * 由容器/远程服务器的 `credential.helper=store` 直接读取，UI 与 git 共用同一份。
 * 内存以 StateFlow 缓存，写操作（save/delete）原子落盘后刷新。
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

    private val _credentials = MutableStateFlow(parse(credentialsFile))
    override fun getAll(): Flow<List<GitCredential>> = _credentials

    override suspend fun findForHost(host: String): GitCredential? {
        val normalized = host.trim().lowercase()
        if (normalized.isEmpty()) return null
        return _credentials.value.firstOrNull { it.host == normalized }
    }

    override suspend fun save(credential: GitCredential) = mutex.withLock {
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
        val updated = _credentials.value.filterNot { it.id == id }
        writeFile(updated)
        _credentials.value = updated
        FileLogger.i(TAG, "删除凭据 id=$id")
    }

    /** 解析 git-credentials 文件；格式异常的行跳过。 */
    private fun parse(file: File): List<GitCredential> {
        if (!file.isFile) return emptyList()
        return runCatching {
            // 文件内容为「base64 后整体反转」的编码串；解码失败（旧版明文）时按原文处理。
            val content = tryDecode(file.readText()) ?: file.readText()
            content.lines().mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                // https://user:token@host
                val at = trimmed.lastIndexOf('@')
                if (at < 0) return@mapNotNull null
                val host = trimmed.substring(at + 1).trim().lowercase()
                val cred = trimmed.substring(0, at)
                val rest = cred.substringAfter("://", "")
                val sep = rest.indexOf(':')
                if (sep < 0) return@mapNotNull null
                val username = URLDecoder.decode(rest.substring(0, sep), "UTF-8")
                val token = URLDecoder.decode(rest.substring(sep + 1), "UTF-8")
                if (host.isEmpty() || username.isEmpty()) null
                else GitCredential(id = host, host = host, username = username, token = token)
            }
        }.getOrElse {
            FileLogger.w(TAG, "解析 git-credentials 失败: ${it.message}")
            emptyList()
        }
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

    /** 解码编码串；不是本格式（如旧版明文）时返回 null。 */
    private fun tryDecode(text: String): String? = runCatching {
        String(Base64.decode(text.reversed(), Base64.DEFAULT), Charsets.UTF_8)
    }.getOrNull()

    /** 把旧版明文凭据文件迁移为编码格式（幂等）。 */
    suspend fun migrateToEncoded() = mutex.withLock {
        if (!credentialsFile.isFile) return@withLock
        val raw = runCatching { credentialsFile.readText() }.getOrNull() ?: return@withLock
        if (tryDecode(raw) != null) return@withLock
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
        val legacyFile = File(File(context.filesDir, LEGACY_DIR), CREDENTIALS_NAME)
        if (!legacyFile.isFile) return@withLock
        val legacy = parse(legacyFile)
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