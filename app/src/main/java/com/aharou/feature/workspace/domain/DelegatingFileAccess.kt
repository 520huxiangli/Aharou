package com.aharou.feature.workspace.domain

import com.aharou.feature.agent.domain.container.ContainerProfileResolver
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [FileAccessProvider] 的委托层：同时持有本地与远程两套实现，每次方法调用时按
 * [ExecutionModeHolder.currentMode] 转发到对应实现。
 *
 * 这样 Hilt 注入时机不再影响最终行为——无论 [FileAccessProvider] 在何时被首次注入，
 * 真正读写文件时才读取当前模式。
 */
@Singleton
class DelegatingFileAccess private constructor(
    private val modeHolder: ExecutionModeHolder,
    private val profileResolver: ContainerProfileResolver,
    private val localLeg: FileAccessProvider,
    private val remoteLeg: FileAccessProvider,
    /** 绑定到某个工作区后就用它解析出的容器；null 表示按全局默认容器/模式。 */
    private val boundWorkspace: String? = null,
) : FileAccessProvider {

    @Inject
    constructor(
        modeHolder: ExecutionModeHolder,
        profileResolver: ContainerProfileResolver,
        localFileAccess: LocalFileAccess,
        remoteSftpFileAccess: RemoteSftpFileAccess,
    ) : this(
        modeHolder,
        profileResolver,
        localFileAccess,
        // 远程腿包一层防崩壳：远程失败（连接失败/主机密钥待确认/SFTP 错误）绝不把异常抛进上层协程（防闪退）。
        SafeFileAccessProvider(remoteSftpFileAccess),
    )

    /**
     * 选腿依据：绑定过工作区就按该工作区解析出的容器，否则看全局默认容器的模式。
     *
     * 只看全局 mode 会让「工作区 → SSH 容器」的覆盖形同虚设：那个工作区的技能、文件工具、
     * 记忆全都会继续读写本地，而命令却跑到了服务器上，两边看到的根本不是同一套文件。
     */
    private fun delegate(): FileAccessProvider {
        val remote = if (boundWorkspace != null) {
            profileResolver.forWorkspace(boundWorkspace).mode == ExecutionMode.REMOTE_SSH
        } else {
            modeHolder.currentMode() == ExecutionMode.REMOTE_SSH
        }
        return if (remote) remoteLeg else localLeg
    }

    /** 绑定会话工作区的副本：本地腿指向该工作区，远程腿不变。 */
    override fun forWorkspace(workspacePath: String): FileAccessProvider {
        if (workspacePath.isBlank()) return this
        return DelegatingFileAccess(
            modeHolder,
            profileResolver,
            localLeg.forWorkspace(workspacePath),
            remoteLeg,
            workspacePath,
        )
    }

    override fun readFile(path: String): String = delegate().readFile(path)

    override fun readLines(path: String): Sequence<String> = delegate().readLines(path)

    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) =
        delegate().writeFile(path, content, overwrite, encoding)

    override fun exists(path: String): Boolean = delegate().exists(path)

    override fun isDirectory(path: String): Boolean = delegate().isDirectory(path)

    override fun isFile(path: String): Boolean = delegate().isFile(path)

    override fun fileSize(path: String): Long = delegate().fileSize(path)

    override fun lastModified(path: String): Long = delegate().lastModified(path)

    override fun fingerprint(path: String): String? = delegate().fingerprint(path)

    override fun permissions(path: String): String = delegate().permissions(path)

    override fun listFiles(path: String): List<FileEntry> = delegate().listFiles(path)

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> =
        delegate().listFilesRecursive(path, maxDepth)

    override fun readBytes(path: String): ByteArray = delegate().readBytes(path)

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) =
        delegate().writeBytes(path, bytes, overwrite)

    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long =
        delegate().writeStream(path, input, overwrite)

    override fun copyToLocal(path: String): File = delegate().copyToLocal(path)

    override fun delete(path: String) = delegate().delete(path)

    override fun deleteRecursively(path: String) = delegate().deleteRecursively(path)

    override fun rename(path: String, newPath: String) = delegate().rename(path, newPath)

    override fun copy(path: String, newPath: String, overwrite: Boolean) = delegate().copy(path, newPath, overwrite)

    override fun move(path: String, newPath: String, overwrite: Boolean) = delegate().move(path, newPath, overwrite)

    override fun mkdirs(path: String) = delegate().mkdirs(path)

    override fun parentPath(path: String): String? = delegate().parentPath(path)

    override fun toDisplayPath(path: String): String = delegate().toDisplayPath(path)
}
