package com.aharou.feature.backup.data

import com.aharou.feature.backup.domain.BackupCrypto
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * 包装输出流使 close() 只 flush 不关闭底层流：写 tar.gz 时会层层 use{}，直接关会把调用方传入的
 * 输出流（如 SAF 的 [OutputStream]）一并关掉；同时转发 write(bytes,off,len) 以避开
 * [FilterOutputStream] 默认的逐字节写。
 */
internal fun OutputStream.bufferedNonClosing(): BufferedOutputStream = object : FilterOutputStream(this) {
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        out.write(bytes, offset, length)
    }

    override fun close() {
        flush()
    }
}.buffered()

/**
 * 导入暂存：把（可能加密的）备份流一次性完整解密并校验到缓存目录，供预览与正式恢复复用同一份，
 * 避免两处各自重新解密。
 *
 * [prepare] 产出的临时文件由调用方负责删除（成功、取消、失败路径都要删），
 * 文件名由 [File.createTempFile] 随机生成、落在应用私有 cacheDir，避免可预测命名导致明文残留被读取。
 */
internal class BackupImportStaging(private val cacheDir: File) {

    /** 解密（明文则直拷）到临时文件并返回；写入失败时删掉半成品再抛出，避免残留。 */
    fun prepare(input: InputStream, password: CharArray?): File {
        val temp = File.createTempFile("backup", ".tmp", cacheDir)
        try {
            FileOutputStream(temp).buffered().use { output ->
                val source = input.buffered()
                val pw = password?.takeIf { it.isNotEmpty() }
                if (pw == null) source.copyTo(output) else BackupCrypto.decryptStream(source, output, pw)
            }
            return temp
        } catch (e: Throwable) {
            temp.delete()
            throw e
        }
    }

    /**
     * 删除历史遗留的暂存文件：正常路径用完即删，进程被杀时来不及删，明文（含 API Key、SSH 口令、
     * 聊天全文）会一直留在 cacheDir。启动时不可能有正在使用的暂存文件，故清理安全；只匹配
     * [File.createTempFile] 生成的 `backup*.tmp`，不误删 cacheDir 中其它缓存。
     */
    fun cleanupStale() {
        cacheDir.listFiles { f -> f.isFile && f.name.startsWith("backup") && f.name.endsWith(".tmp") }
            ?.forEach { runCatching { it.delete() } }
    }
}

/**
 * 打开的 tar 流封装：加密导入时附带解密用的临时文件，关闭时一并清理。
 * 供 [BackupManagerImpl] 与本文件共用，故由原私有嵌套类提升为顶层 internal。
 */
internal class TarSource(
    val tar: TarArchiveInputStream,
    private val temp: File?
) : AutoCloseable {
    override fun close() {
        runCatching { tar.close() }
        temp?.delete()
    }
}
