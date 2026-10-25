package com.aharou.feature.workspace.domain

import com.aharou.core.util.FileLogger
import com.aharou.core.util.boundedLines
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [FileAccessProvider] 的本地实现：包一层 [WorkspacePathMapper]，走 java.io.File 直读。
 *
 * 路径解析会逐段展开容器软链（[WorkspacePathMapper.resolveFile]）；但删除 / 重命名动的是链接本身，
 * 递归遍历与递归删除也一律不跟随链接——否则一个自指或指错的链接就能把操作扩散到链接目标上去。
 */
@Singleton
class LocalFileAccess @Inject constructor(
    private val pathMapper: WorkspacePathMapper
) : FileAccessProvider {

    /** 会话绑定的工作区路径；null = 跟随全局当前工作区。 */
    private var boundWorkspace: String? = null

    /**
     * @param followFinalLink 末级是软链时是否解析到目标。默认跟随（读写内容）；
     * 删除 / 重命名传 false，动链接本身而不是它指向的文件。
     */
    private fun resolve(path: String, followFinalLink: Boolean = true): File =
        pathMapper.resolveFile(path, boundWorkspace, followFinalLink)

    /** 条目是否存在——用 NOFOLLOW 判存在，指向已删目标的断链也算存在（要能删掉它）。 */
    private fun entryExists(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    /**
     * 递归删除 [file]，不跟随链接：链接只删链接本身，不进入它指向的目录。
     *
     * 为什么不用 Kotlin 的 `File.deleteRecursively()`：它走 walkBottomUp，对目录链接同样会 listFiles
     * 下钻，遇到指向上级目录或 `/` 的链接就会把删除扩散到链接目标（工作区、rootfs）。
     */
    private fun deleteTree(file: File) {
        if (!entryExists(file)) return
        Files.walkFileTree(file.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(entry: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(entry)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    override fun forWorkspace(workspacePath: String): FileAccessProvider {
        if (workspacePath.isBlank()) return this
        return LocalFileAccess(pathMapper).also { it.boundWorkspace = workspacePath }
    }

    override fun readFile(path: String): String {
        val file = resolve(path)
        if (!file.exists()) throw NoSuchFileException(file)
        return file.readText()
    }

    override fun readLines(path: String): Sequence<String> {
        val file = resolve(path)
        if (!file.exists()) throw NoSuchFileException(file)
        // 惰性 Sequence：每次迭代才读下一行，大文件不整份载入；迭代结束后随 use 关闭 reader。
        // 单行封顶 64K 字符，避免单个超长行把内存顶爆。
        return boundedLines { file.bufferedReader() }
    }

    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) {
        val file = resolve(path)
        if (file.exists() && !overwrite) throw FileAlreadyExistsException(file)
        writeAtomically(file, content.toByteArray(encoding), path)
    }

    override fun exists(path: String): Boolean = resolve(path).exists()

    override fun isDirectory(path: String): Boolean = resolve(path).isDirectory

    override fun isFile(path: String): Boolean = resolve(path).isFile

    override fun fileSize(path: String): Long = resolve(path).length()

    override fun lastModified(path: String): Long = resolve(path).lastModified()

    override fun fingerprint(path: String): String? {
        val file = resolve(path)
        // 目录等非普通文件没有「内容版本」可言，返回 null 让调用方放行。
        if (!file.isFile) return null
        return sha256Fingerprint(file.readBytes())
    }

    override fun permissions(path: String): String {
        val file = resolve(path)
        val read = if (file.canRead()) "r" else "-"
        val write = if (file.canWrite()) "w" else "-"
        val execute = if (file.canExecute() || file.isDirectory) "x" else "-"
        return read + write + execute
    }

    override fun listFiles(path: String): List<FileEntry> {
        val dir = resolve(path)
        return dir.listFiles().orEmpty().map { child ->
            FileEntry(
                name = child.name,
                isDirectory = child.isDirectory,
                size = child.length(),
                lastModified = child.lastModified(),
                localFile = child,
                permissions = run {
                    val r = if (child.canRead()) "r" else "-"
                    val w = if (child.canWrite()) "w" else "-"
                    val x = if (child.canExecute() || child.isDirectory) "x" else "-"
                    r + w + x
                }
            )
        }
    }

    override fun readBytes(path: String): ByteArray {
        val file = resolve(path)
        if (!file.exists()) throw NoSuchFileException(file)
        return file.readBytes()
    }

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> {
        val dir = resolve(path)
        if (!dir.isDirectory) return emptyList()
        val root = dir.toPath()
        val paths = mutableListOf<String>()
        // 为什么不用 walkTopDown：它跟 JDK/Kotlin 的目录遍历一样会进入软链目录，容器里指向上级
        // 或自身的链接会变成无限递归（卡死工具调用、甚至栈溢出）。walkFileTree 默认不跟随链接，
        // 链接条目只按「是否指向普通文件」计入，保持调用方看到的文件集合不变。
        try {
            Files.walkFileTree(
                root,
                emptySet<FileVisitOption>(),
                maxDepth.coerceAtLeast(0),
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile || (attrs.isSymbolicLink && file.toFile().isFile)) {
                            paths += root.relativize(file).joinToString("/")
                        }
                        return FileVisitResult.CONTINUE
                    }

                    // 无权限/已消失的子目录跳过（与原先 listFiles 返回 null 就跳过的行为一致）。
                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult =
                        FileVisitResult.CONTINUE
                }
            )
        } catch (e: IOException) {
            FileLogger.w(TAG, "递归列目录中断: ${dir.absolutePath}", e)
        }
        return paths
    }

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
        val file = resolve(path)
        if (file.exists() && !overwrite) throw FileAlreadyExistsException(file)
        writeAtomically(file, bytes, path)
    }

    /**
     * 原子写：同目录临时文件 → 写入 → fsync → 回读长度校验 → 原子替换目标。
     *
     * 为什么不再直接写目标文件：
     * - **读侧一致性**：原地 TRUNC 写会让并发的读者看到半截内容（多会话/编辑器同时读同一文件时是真实现象）；
     * - **失败不留半个文件**：中途失败（ENOSPC、进程被杀）时目标文件保持原内容，而不是半个文件；
     * - 替换本身原子，目标要么是旧内容、要么是新内容，不存在中间态。
     *
     * 保留原有的 fsync + 长度回读校验：`writeText` 只写 page cache，延迟分配下的写回错误
     * （磁盘已满 ENOSPC、存储异常）不会报给调用方，会变成「工具返回 success 但文件没变」。
     * `fd.sync()` 把错误提前成 IOException，让工具报真实失败而不是静默丢掉改动。
     *
     * 临时文件必须与目标**同目录**（同一文件系统）才能原子替换；放系统临时目录会退化成跨设备复制。
     */
    private fun writeAtomically(file: File, bytes: ByteArray, displayPath: String) {
        val parent = file.parentFile
        parent?.mkdirs()

        val tmp = File.createTempFile(TMP_FILE_PREFIX, TMP_FILE_SUFFIX, parent)
        // 本函数所有退出路径都会显式清理或改名，不依赖 JVM 退出时的 deleteOnExit。
        tmp.deleteOnExit()
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            val actual = tmp.length()
            if (actual != bytes.size.toLong()) {
                throw IOException("write verification failed: ${file.absolutePath} expected ${bytes.size} bytes, found $actual")
            }
            replaceTarget(tmp, file)
            FileLogger.i(TAG, "写入: '$displayPath' -> ${file.absolutePath} (${bytes.size} 字节)")
        } finally {
            // 替换成功后 tmp 已不存在，这里是失败路径与异常路径的兜底清理。
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * 用 [tmp] 替换 [file]。真正的替换逻辑在 [moveReplacing] 里，此处只负责提供文件系统操作与降级日志。
     */
    private fun replaceTarget(tmp: File, file: File) {
        try {
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: AtomicMoveNotSupportedException) {
            FileLogger.w(TAG, "文件系统不支持原子替换，退化为删除后改名: ${file.absolutePath}", e)
            moveReplacing(
                exists = { file.exists() },
                delete = { file.delete() },
                move = { from, to -> from.renameTo(to) },
                tmp = tmp,
                target = file
            )
        }
    }

    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long {
        val file = resolve(path)
        if (file.exists() && !overwrite) throw FileAlreadyExistsException(file)
        file.parentFile?.mkdirs()
        return try {
            FileOutputStream(file).use { out -> input.copyTo(out) }
        } catch (e: Exception) {
            // 写入中途失败（如源流读取出错、空间不足）会留下半截文件，清掉再抛出
            file.delete()
            throw e
        }
    }

    override fun copyToLocal(path: String): File = resolve(path)

    override fun delete(path: String) {
        resolve(path, followFinalLink = false).delete()
    }

    override fun deleteRecursively(path: String) {
        deleteTree(resolve(path, followFinalLink = false))
    }

    override fun rename(path: String, newPath: String) {
        val source = resolve(path, followFinalLink = false)
        val target = resolve(newPath, followFinalLink = false)
        if (!source.exists()) throw NoSuchFileException(source)
        if (target.exists()) throw FileAlreadyExistsException(target)
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) {
            throw IOException("rename failed: ${source.absolutePath} -> ${target.absolutePath}")
        }
    }

    override fun copy(path: String, newPath: String, overwrite: Boolean) {
        val source = resolve(path)
        val target = resolve(newPath, followFinalLink = false)
        if (!source.exists()) throw NoSuchFileException(source)
        val sourcePath = source.canonicalFile.toPath()
        val targetPath = target.canonicalFile.toPath()
        if (targetPath == sourcePath || (source.isDirectory && targetPath.startsWith(sourcePath))) {
            throw IOException("destination is the source or its descendant: $newPath")
        }
        if (entryExists(target)) {
            if (!overwrite) throw FileAlreadyExistsException(target)
            deleteTree(target)
        }
        target.parentFile?.mkdirs()
        if (source.isDirectory) {
            source.copyRecursively(target, overwrite = false)
        } else {
            source.copyTo(target, overwrite = false)
        }
    }

    override fun move(path: String, newPath: String, overwrite: Boolean) {
        val source = resolve(path, followFinalLink = false)
        val target = resolve(newPath, followFinalLink = false)
        if (!entryExists(source)) throw NoSuchFileException(source)
        val sourcePath = source.canonicalFile.toPath()
        val targetPath = target.canonicalFile.toPath()
        if (targetPath == sourcePath || (source.isDirectory && targetPath.startsWith(sourcePath))) {
            throw IOException("destination is the source or its descendant: $newPath")
        }
        if (entryExists(target)) {
            if (!overwrite) throw FileAlreadyExistsException(target)
            deleteTree(target)
        }
        target.parentFile?.mkdirs()
        val moved = try {
            source.renameTo(target)
        } catch (e: SecurityException) {
            false
        }
        if (moved) return
        // 回退：复制后删除源（复制中断时源保留，下次重试安全）。
        if (source.isDirectory) {
            source.copyRecursively(target, overwrite = false)
        } else {
            source.copyTo(target, overwrite = false)
        }
        deleteTree(source)
    }

    override fun mkdirs(path: String) {
        resolve(path).mkdirs()
    }

    override fun parentPath(path: String): String? {
        // 回显统一走未解析路径：用户传的是链接路径，返回的目标路径会让他下次找不到入口。
        val parent = pathMapper.toHostFile(path, boundWorkspace).parentFile ?: return null
        return pathMapper.toContainerPath(parent.absolutePath, boundWorkspace)
    }

    override fun toDisplayPath(path: String): String {
        return pathMapper.toContainerPath(pathMapper.toHostFile(path, boundWorkspace).absolutePath, boundWorkspace)
    }

    private companion object {
        const val TAG = "LocalFileAccess"

        /**
         * 原子写临时文件前缀。带 `.aicode-` 前缀便于在异常残留时被用户与清理逻辑识别；
         * `File.createTempFile` 要求前缀至少 3 字符。
         */
        const val TMP_FILE_PREFIX = ".aicode-tmp-"

        /** 临时文件后缀。与 [TMP_FILE_PREFIX] 一起把临时文件与正常文件区分开。 */
        const val TMP_FILE_SUFFIX = ".part"
    }
}

/**
 * 原子替换的**降级路径**：文件系统不支持 `ATOMIC_MOVE` 时，先删目标再改名。
 *
 * 抽成顶层函数并让文件操作以参数传入，是为了让「目标删不掉」与「改名失败」这两条异常路径能被单测
 * 直接覆盖——它们用真实文件系统很难稳定触发（尤其在 Windows 上）。调用方负责日志。
 *
 * 语义要点：
 * - 目标不存在时直接改名，不做多余的删除；
 * - **删不掉目标就抛错，绝不继续改名**：继续改会在目标仍存在时失败，反而把"目标完好"这个较好的结果弄坏；
 * - 改名失败时把 [tmp] 删掉，不给用户留下垃圾文件。
 *
 * @param exists 目标是否存在
 * @param delete 删除目标，成功返回 true
 * @param move 把 [tmp] 改名到 [target]，成功返回 true
 * @throws IOException 目标删不掉或改名失败
 */
internal fun moveReplacing(
    exists: () -> Boolean,
    delete: () -> Boolean,
    move: (from: File, to: File) -> Boolean,
    tmp: File,
    target: File
) {
    if (exists() && !delete()) {
        tmp.delete()
        throw IOException("atomic replace failed to clear target: ${target.absolutePath}")
    }
    if (!move(tmp, target)) {
        tmp.delete()
        throw IOException("atomic replace failed: ${tmp.absolutePath} -> ${target.absolutePath}")
    }
}
