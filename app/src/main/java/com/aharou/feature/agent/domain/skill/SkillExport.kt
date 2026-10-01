package com.aharou.feature.agent.domain.skill

import com.aharou.core.util.FileLogger
import com.aharou.feature.workspace.domain.FileAccessProvider
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 技能导出：把一个技能目录（含附带的脚本等全部文件）打成一个 zip。
 *
 * 包结构与 [SkillImporter] 认的输入一致——顶层是技能目录、里面是各文件，
 * 所以导出的包能直接导回本 App，也能给别家按同一约定实现的工具用。
 *
 * 与导入共用 [FileAccessProvider] + 容器路径，本地工作区与远程 SSH 工作区同一套逻辑。
 */
internal object SkillExporter {

    private const val TAG = "SkillExporter"

    /** 体积与条目上限，与导入侧保持一致。 */
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
    private const val MAX_ENTRIES = 2000

    /**
     * 把 [dirPath] 整棵目录打包，zip 内顶层目录名为 [rootName]。
     * 目录为空、不存在或读取失败时返回 null。
     */
    fun zip(provider: FileAccessProvider, dirPath: String, rootName: String): ByteArray? {
        val dir = dirPath.trimEnd('/')
        val files = mutableListOf<Pair<String, ByteArray>>()
        return try {
            collect(provider, dir, "", files)
            if (files.isEmpty()) null else pack(rootName, files)
        } catch (e: Exception) {
            FileLogger.e(TAG, "导出技能失败：$dir", e)
            null
        }
    }

    /** 递归收集文件，[relative] 是相对技能根的路径前缀。 */
    private fun collect(
        provider: FileAccessProvider,
        dir: String,
        relative: String,
        out: MutableList<Pair<String, ByteArray>>
    ) {
        val entries = runCatching { provider.listFiles(dir) }.getOrElse { e ->
            FileLogger.w(TAG, "列出目录失败：$dir（${e.message}）")
            return
        }
        var total = out.sumOf { it.second.size.toLong() }
        for (entry in entries) {
            if (out.size >= MAX_ENTRIES || total >= MAX_TOTAL_BYTES) return
            val child = "$dir/${entry.name}"
            val path = if (relative.isEmpty()) entry.name else "$relative/${entry.name}"
            if (entry.isDirectory) {
                collect(provider, child, path, out)
            } else {
                // 单个文件读不到就跳过它，别让一个坏文件毁掉整次导出
                val bytes = runCatching { provider.readBytes(child) }.getOrNull() ?: continue
                out += path to bytes
                total += bytes.size
            }
        }
    }

    /** 用内存里的文件打一个 zip（内置技能没有磁盘目录，正文从 assets 现攒）。 */
    fun zipOf(rootName: String, files: Map<String, ByteArray>): ByteArray? =
        if (files.isEmpty()) null else pack(rootName, files.toList())

    private fun pack(rootName: String, files: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry("${rootName.trim('/')}/$path"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
