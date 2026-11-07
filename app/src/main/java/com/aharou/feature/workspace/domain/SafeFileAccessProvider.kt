package com.aharou.feature.workspace.domain

import com.aharou.core.util.FileLogger
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

/**
 * 远程读取失败时保留默认值，避免后台读取失败导致闪退；写入失败必须向调用方报告。
 */
class SafeFileAccessProvider(
    private val delegate: FileAccessProvider,
) : FileAccessProvider by delegate {

    private fun <T> guard(op: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        FileLogger.w("SafeFileAccess", "远程文件操作失败 [$op]: ${t.message}")
        fallback
    }

    private fun <T> writeGuard(op: String, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        FileLogger.w("SafeFileAccess", "远程文件操作失败 [$op]: ${t.message}")
        throw t
    }

    override fun forWorkspace(workspacePath: String): FileAccessProvider =
        if (workspacePath.isBlank()) this else SafeFileAccessProvider(delegate.forWorkspace(workspacePath))

    override fun readFile(path: String): String = guard("readFile", "") { delegate.readFile(path) }

    override fun readLines(path: String): Sequence<String> =
        guard("readLines", emptySequence()) { delegate.readLines(path) }

    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) {
        writeGuard("writeFile") { delegate.writeFile(path, content, overwrite, encoding) }
    }

    override fun exists(path: String): Boolean = guard("exists", false) { delegate.exists(path) }

    override fun isDirectory(path: String): Boolean = guard("isDirectory", false) { delegate.isDirectory(path) }

    override fun isFile(path: String): Boolean = guard("isFile", false) { delegate.isFile(path) }

    override fun fileSize(path: String): Long = guard("fileSize", 0L) { delegate.fileSize(path) }

    override fun lastModified(path: String): Long = guard("lastModified", 0L) { delegate.lastModified(path) }

    override fun permissions(path: String): String = guard("permissions", "") { delegate.permissions(path) }

    override fun listFiles(path: String): List<FileEntry> =
        guard("listFiles", emptyList()) { delegate.listFiles(path) }

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> =
        guard("listFilesRecursive", emptyList()) { delegate.listFilesRecursive(path, maxDepth) }

    override fun readBytes(path: String): ByteArray = guard("readBytes", ByteArray(0)) { delegate.readBytes(path) }

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
        writeGuard("writeBytes") { delegate.writeBytes(path, bytes, overwrite) }
    }

    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long =
        writeGuard("writeStream") { delegate.writeStream(path, input, overwrite) }

    override fun copyToLocal(path: String): File = guard("copyToLocal", File("")) { delegate.copyToLocal(path) }

    override fun delete(path: String) {
        writeGuard("delete") { delegate.delete(path) }
    }

    override fun deleteRecursively(path: String) {
        writeGuard("deleteRecursively") { delegate.deleteRecursively(path) }
    }

    override fun rename(path: String, newPath: String) {
        writeGuard("rename") { delegate.rename(path, newPath) }
    }

    override fun copy(path: String, newPath: String, overwrite: Boolean) {
        writeGuard("copy") { delegate.copy(path, newPath, overwrite) }
    }

    override fun move(path: String, newPath: String, overwrite: Boolean) {
        writeGuard("move") { delegate.move(path, newPath, overwrite) }
    }

    override fun mkdirs(path: String) {
        writeGuard("mkdirs") { delegate.mkdirs(path) }
    }

    override fun parentPath(path: String): String? = guard("parentPath", null) { delegate.parentPath(path) }

    override fun toDisplayPath(path: String): String = guard("toDisplayPath", path) { delegate.toDisplayPath(path) }
}
