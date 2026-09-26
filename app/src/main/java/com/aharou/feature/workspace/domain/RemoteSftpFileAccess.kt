package com.aharou.feature.workspace.domain

import com.aharou.core.util.BoundedLineReader
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.RemoteSshConnection
import com.aharou.feature.agent.domain.container.friendlySshError
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import com.aharou.feature.workspace.domain.WorkspacePathMapper.Companion.CONTAINER_ROOT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.xfer.FilePermission
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.util.EnumSet
import javax.inject.Inject

private const val TAG = "RemoteSftpFileAccess"

/** 单次 SFTP 读写缓冲大小。 */
private const val IO_CHUNK = 32 * 1024

/** `readFile` / `readBytes` 的远程文件大小上限（字节）：超过即抛 [RemoteOutputTooLargeException]，避免整篇读进内存。 */
private const val MAX_REMOTE_READ_BYTES = 8L * 1024 * 1024

/** 远程文件超过上限时抛出：调用方据此给出「内容过大」提示，而不是误判为文件不存在。 */
class RemoteOutputTooLargeException(message: String) : IOException(message)

/**
 * [FileAccessProvider] 的远程实现：走 SFTP 协议读写远程文件。
 *
 * **独立通道**：SFTP 跑在 [RemoteSshConnection.sftp] 建立的第二条 SSH transport 上，与 Bash/命令的
 * exec 通道隔离——sshj 的 SFTP 有间歇性 Buffer 溢出（hierynomus/sshj#461），共用 transport 时崩溃会
 * 一并拖垮命令通道；分开后只影响文件读写，通道失效时丢弃、下次调用自动重建。
 *
 * 路径映射：AI 给的 `~/workspace/...` 映射到当前选中工作区的远程绝对路径；其它绝对路径（如 `/etc/...`）
 * 直接作为远程绝对路径使用。sshj 的 [SFTPClient] 非线程安全，所有操作经 [sftpMutex] 串行化。
 */
class RemoteSftpFileAccess @Inject constructor(
    private val connection: RemoteSshConnection,
    private val workspaceRepository: WorkspaceRepository
) : FileAccessProvider {

    private val sftpMutex = Mutex()

    /** 当前选中工作区在远程服务器上的真实路径（如 /data/.../test/111）。 */
    private fun currentWorkspaceRoot(): String {
        val cfg = connection.config ?: throw IllegalStateException("SSH 未连接")
        // currentPath() 远程模式返回选中工作区的远程绝对路径；未选中时回退到 remoteWorkspacePath
        val path = workspaceRepository.currentPath()
        return if (path.isNotBlank() && path != "/") path else cfg.remoteWorkspacePath.trimEnd('/')
    }

    /** 把 AI 路径映射到远程服务器上的真实路径。 */
    private fun toRemotePath(path: String): String =
        remotePathFor(path, currentWorkspaceRoot(), connection.remoteHome)

    /** 把远程路径还原为 AI 视角的容器路径（回显用）。 */
    private fun toDisplayPathFromRemote(remotePath: String): String =
        displayPathFor(remotePath, currentWorkspaceRoot())

    /**
     * 在独立 SFTP 通道上串行执行 [block]。传输层异常时丢弃当前通道（下次调用自动重建）后原样抛出；
     * 业务错误（文件不存在/已存在、SFTP 状态码错误）不重建。不做自动重试——写操作重试可能重复落盘。
     */
    private fun <T> withSftp(block: (SFTPClient) -> T): T = runBlocking {
        withContext(Dispatchers.IO) {
            sftpMutex.withLock {
                val sftp = try {
                    connection.sftp()
                } catch (e: Exception) {
                    throw IOException(friendlySshError(e), e)
                }
                guarded { block(sftp) }
            }
        }
    }

    /**
     * 复用已打开的 SFTP 通道执行一次操作（不重新取 client）。供 [readLines] 惰性迭代使用：每次只锁住
     * 一次读取，`yield` 在锁外，调用方中途放弃迭代时不会把 [sftpMutex] 永久占住。
     */
    private fun <T> onSftp(block: () -> T): T = runBlocking {
        withContext(Dispatchers.IO) { sftpMutex.withLock { guarded(block) } }
    }

    /** 传输层异常时丢弃 SFTP 通道（下次调用自动重建）后原样抛出；业务错误不重建。 */
    private suspend fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        if (e !is SFTPException && e !is NoSuchFileException && e !is FileAlreadyExistsException) {
            runCatching { connection.invalidateSftp() }
        }
        throw e
    }

    override fun readFile(path: String): String = String(readAll(toRemotePath(path)), Charsets.UTF_8)

    /**
     * 惰性逐行读取：每次只物化当前一行（单行封顶 64K 字符），读到哪算哪，整文件不进内存。
     * 序列可重复迭代（每次迭代重新打开远程文件）；调用方需在 IO 线程上迭代。
     */
    override fun readLines(path: String): Sequence<String> {
        val remote = toRemotePath(path)
        return sequence {
            val reader = withSftp { sftp ->
                val attrs = sftp.statExistence(remote) ?: throw NoSuchFileException(File(remote))
                if (attrs.type == FileMode.Type.DIRECTORY) throw IOException("是目录，无法按文件读取: $remote")
                BoundedLineReader(InputStreamReader(RemoteFileInputStream(sftp.open(remote))))
            }
            try {
                while (true) {
                    val line = onSftp { reader.readLine() } ?: break
                    yield(line.text)
                }
            } finally {
                runCatching { onSftp { reader.close() } }
            }
        }
    }

    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) =
        writeBytes(path, content.toByteArray(encoding), overwrite)

    override fun exists(path: String): Boolean = withSftp { it.statExistence(toRemotePath(path)) != null }

    override fun isDirectory(path: String): Boolean =
        withSftp { it.statExistence(toRemotePath(path))?.type == FileMode.Type.DIRECTORY }

    override fun isFile(path: String): Boolean =
        withSftp { it.statExistence(toRemotePath(path))?.type == FileMode.Type.REGULAR }

    override fun fileSize(path: String): Long =
        withSftp { it.statExistence(toRemotePath(path))?.size ?: 0L }

    override fun lastModified(path: String): Long =
        withSftp { (it.statExistence(toRemotePath(path))?.mtime ?: 0L) * 1000L }

    override fun permissions(path: String): String =
        withSftp { it.statExistence(toRemotePath(path))?.permissions?.let(::formatPermissions) ?: "---" }

    override fun listFiles(path: String): List<FileEntry> {
        val remote = toRemotePath(path)
        return withSftp { sftp ->
            sftp.ls(remote).mapNotNull { info ->
                val name = info.name
                if (name == "." || name == "..") return@mapNotNull null
                FileEntry(
                    name = name,
                    isDirectory = info.isDirectory,
                    size = info.attributes.size,
                    lastModified = info.attributes.mtime * 1000L,
                    localFile = null,
                    permissions = formatPermissions(info.attributes.permissions)
                )
            }
        }
    }

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> {
        val remote = toRemotePath(path)
        return withSftp { sftp ->
            val result = mutableListOf<String>()
            fun walk(dir: String, rel: String, depth: Int) {
                val entries = runCatching { sftp.ls(dir) }.getOrNull() ?: return
                for (info in entries) {
                    val name = info.name
                    if (name == "." || name == "..") continue
                    val childRel = if (rel.isEmpty()) name else "$rel/$name"
                    if (info.isDirectory) {
                        if (depth < maxDepth) walk(info.path, childRel, depth + 1)
                    } else {
                        result += childRel
                    }
                }
            }
            walk(remote, "", 1)
            result
        }
    }

    override fun readBytes(path: String): ByteArray = readAll(toRemotePath(path))

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
        val remote = toRemotePath(path)
        withSftp { sftp ->
            if (sftp.statExistence(remote) != null && !overwrite) throw FileAlreadyExistsException(File(remote))
            ensureParent(sftp, remote)
            sftp.open(remote, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { rf ->
                writeAll(rf, bytes)
            }
        }
    }

    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long {
        val remote = toRemotePath(path)
        // 先落到 .aicode-part 再改名：传输中途断开时不会在目标位置留下半截文件
        val tmp = "$remote.aicode-part"
        return withSftp { sftp ->
            if (sftp.statExistence(remote) != null && !overwrite) throw FileAlreadyExistsException(File(remote))
            ensureParent(sftp, remote)
            val written = try {
                sftp.open(tmp, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { rf ->
                    val buf = ByteArray(IO_CHUNK)
                    var offset = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        rf.write(offset, buf, 0, n)
                        offset += n
                    }
                    offset
                }
            } catch (e: Exception) {
                runCatching { sftp.rm(tmp) }
                throw e
            }
            // 目标已存在时先删再改名（SFTP v3 无跨平台可靠的「覆盖改名」）
            if (sftp.statExistence(remote) != null) runCatching { sftp.rm(remote) }
            sftp.rename(tmp, remote)
            written
        }
    }

    override fun copyToLocal(path: String): File {
        val remote = toRemotePath(path)
        val tempFile = File.createTempFile("aicode_remote_", ".copy").apply { deleteOnExit() }
        return try {
            withSftp { sftp ->
                if (sftp.statExistence(remote) == null) throw NoSuchFileException(File(remote))
                sftp.get(remote, tempFile.absolutePath)
            }
            tempFile
        } catch (e: Exception) {
            // 用完即删：临时文件不依赖 deleteOnExit（只在进程退出清），失败路径也回收，避免长会话累积。
            runCatching { tempFile.delete() }
            if (e is NoSuchFileException) throw e
            FileLogger.e(TAG, "copyToLocal 失败: $remote", e)
            throw NoSuchFileException(File(remote))
        }
    }

    override fun delete(path: String) {
        val remote = toRemotePath(path)
        // 接口约定 delete 只删文件或空目录：目录非空时 rmdir 失败 → 抛异常（与本地 File.delete() 对齐）。
        withSftp { sftp ->
            val attrs = sftp.statExistence(remote) ?: return@withSftp
            if (attrs.type == FileMode.Type.DIRECTORY) sftp.rmdir(remote) else sftp.rm(remote)
        }
    }

    override fun deleteRecursively(path: String) {
        val remote = toRemotePath(path)
        withSftp { sftp -> deleteRecursive(sftp, remote) }
    }

    override fun rename(path: String, newPath: String) {
        val from = toRemotePath(path)
        val to = toRemotePath(newPath)
        withSftp { sftp ->
            if (sftp.statExistence(from) == null) throw NoSuchFileException(File(from))
            if (sftp.statExistence(to) != null) throw FileAlreadyExistsException(File(to))
            sftp.rename(from, to)
        }
    }

    override fun copy(path: String, newPath: String, overwrite: Boolean) {
        val from = toRemotePath(path)
        val to = toRemotePath(newPath)
        withSftp { sftp ->
            if (sftp.statExistence(from) == null) throw NoSuchFileException(File(from))
            if (sftp.statExistence(to) != null) {
                if (!overwrite) throw FileAlreadyExistsException(File(to))
                deleteRecursive(sftp, to)
            }
            copyRecursive(sftp, from, to)
        }
    }

    override fun move(path: String, newPath: String, overwrite: Boolean) {
        val from = toRemotePath(path)
        val to = toRemotePath(newPath)
        withSftp { sftp ->
            if (sftp.statExistence(from) == null) throw NoSuchFileException(File(from))
            if (sftp.statExistence(to) != null) {
                if (!overwrite) throw FileAlreadyExistsException(File(to))
                deleteRecursive(sftp, to)
            }
            sftp.rename(from, to)
        }
    }

    override fun mkdirs(path: String) {
        val remote = toRemotePath(path)
        withSftp { it.mkdirs(remote) }
    }

    override fun parentPath(path: String): String? {
        val remote = toRemotePath(path)
        val parent = remote.substringBeforeLast('/', "")
        if (parent.isEmpty()) return null
        return toDisplayPathFromRemote(parent)
    }

    override fun toDisplayPath(path: String): String = toDisplayPathFromRemote(toRemotePath(path))

    /** 读取远程文件全部字节；不存在抛 [NoSuchFileException]，超过 [MAX_REMOTE_READ_BYTES] 抛 [RemoteOutputTooLargeException]。 */
    private fun readAll(remote: String): ByteArray = withSftp { sftp ->
        val attrs = sftp.statExistence(remote) ?: throw NoSuchFileException(File(remote))
        if (attrs.type == FileMode.Type.DIRECTORY) throw IOException("是目录，无法按文件读取: $remote")
        if (attrs.size > MAX_REMOTE_READ_BYTES) {
            throw RemoteOutputTooLargeException(
                "远程文件超过 ${MAX_REMOTE_READ_BYTES / 1024 / 1024}MB，请改用 start_line/end_line 分段读取"
            )
        }
        sftp.open(remote).use { rf -> readFully(rf) }
    }

    private fun readFully(rf: RemoteFile): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(IO_CHUNK)
        var offset = 0L
        while (true) {
            val n = rf.read(offset, buf, 0, buf.size)
            if (n <= 0) break
            out.write(buf, 0, n)
            offset += n
        }
        return out.toByteArray()
    }

    private fun writeAll(rf: RemoteFile, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val n = minOf(IO_CHUNK, bytes.size - offset)
            rf.write(offset.toLong(), bytes, offset, n)
            offset += n
        }
    }

    /** 递归删除（SFTP 无递归删除原语）：后序遍历，目录在子项删完后 rmdir。 */
    private fun deleteRecursive(sftp: SFTPClient, remote: String) {
        val attrs = sftp.statExistence(remote) ?: return
        if (attrs.type == FileMode.Type.DIRECTORY) {
            for (info in sftp.ls(remote)) {
                val name = info.name
                if (name == "." || name == "..") continue
                deleteRecursive(sftp, info.path)
            }
            sftp.rmdir(remote)
        } else {
            sftp.rm(remote)
        }
    }

    /** 递归复制（SFTP 无服务端复制原语）：目录建好后逐项复制，文件流式读写。 */
    private fun copyRecursive(sftp: SFTPClient, from: String, to: String) {
        val attrs = sftp.statExistence(from) ?: throw NoSuchFileException(File(from))
        if (attrs.type == FileMode.Type.DIRECTORY) {
            sftp.mkdirs(to)
            for (info in sftp.ls(from)) {
                val name = info.name
                if (name == "." || name == "..") continue
                copyRecursive(sftp, info.path, "$to/$name")
            }
        } else {
            sftp.open(from).use { src ->
                sftp.open(to, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { dst ->
                    val buf = ByteArray(IO_CHUNK)
                    var offset = 0L
                    while (true) {
                        val n = src.read(offset, buf, 0, buf.size)
                        if (n <= 0) break
                        dst.write(offset, buf, 0, n)
                        offset += n
                    }
                }
            }
        }
    }

    private fun ensureParent(sftp: SFTPClient, remote: String) {
        val parent = remote.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) sftp.mkdirs(parent)
    }
}

/** 把 SFTP [RemoteFile] 的随机读适配为顺序 [InputStream]，供 [BoundedLineReader] 惰性逐行读取。 */
private class RemoteFileInputStream(private val file: RemoteFile) : InputStream() {
    private var offset = 0L
    private val single = ByteArray(1)

    override fun read(): Int {
        val n = file.read(offset, single, 0, 1)
        if (n <= 0) return -1
        offset += 1
        return single[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val n = file.read(offset, b, off, len)
        if (n <= 0) return -1
        offset += n
        return n
    }

    override fun close() = file.close()
}

/** SFTP 权限集合 → 9 位 rwx 字符串（如 `rw-r--r--`）。 */
internal fun formatPermissions(perms: Set<FilePermission>): String {
    fun bit(p: FilePermission, c: Char) = if (p in perms) c else '-'
    return buildString {
        append(bit(FilePermission.USR_R, 'r'))
        append(bit(FilePermission.USR_W, 'w'))
        append(bit(FilePermission.USR_X, 'x'))
        append(bit(FilePermission.GRP_R, 'r'))
        append(bit(FilePermission.GRP_W, 'w'))
        append(bit(FilePermission.GRP_X, 'x'))
        append(bit(FilePermission.OTH_R, 'r'))
        append(bit(FilePermission.OTH_W, 'w'))
        append(bit(FilePermission.OTH_X, 'x'))
    }
}

/** AI 路径 → 远程真实路径：`~/workspace` 前缀映射到 [workspaceRoot]，其它绝对路径原样使用。 */
internal fun remotePathFor(path: String, workspaceRoot: String, remoteHome: String?): String {
    val root = workspaceRoot.trimEnd('/')
    // CONTAINER_ROOT 是 ~/workspace，展开 ~ 后做前缀匹配
    val wsRoot = (remoteHome ?: "~").trimEnd('/') + "/workspace"
    val p = path.trim().let {
        if (it.startsWith("~/")) {
            val home = remoteHome
            if (home != null) home.trimEnd('/') + "/" + it.removePrefix("~/") else it
        } else it
    }
    return when {
        p == wsRoot || p == "$wsRoot/" || p == CONTAINER_ROOT || p == "$CONTAINER_ROOT/" -> root
        p.startsWith("$wsRoot/") ->
            root + "/" + p.removePrefix("$wsRoot/")
        p.startsWith("/") -> p
        else -> root + "/" + p
    }
}

/** 远程真实路径 → AI 视角的容器路径（回显用）。 */
internal fun displayPathFor(remotePath: String, workspaceRoot: String): String {
    val root = workspaceRoot.trimEnd('/')
    return when {
        remotePath == root -> CONTAINER_ROOT
        remotePath.startsWith("$root/") -> CONTAINER_ROOT + "/" + remotePath.removePrefix("$root/")
        else -> remotePath
    }
}
