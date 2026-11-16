package com.aharou.feature.backup.data

import com.aharou.core.util.FileLogger
import com.aharou.feature.plugin.runtime.PluginBackupArchiveService
import com.aharou.feature.workspace.domain.FileReadTooLargeException

internal const val PLUGIN_ARCHIVE_PREFIX = "plugins/"

private const val TAG = "PluginBackupWriter"
private const val MAX_PLUGIN_TOTAL_BYTES = 32L * 1024 * 1024
private const val MAX_PLUGIN_FILE_BYTES = 8L * 1024 * 1024

/**
 * 把插件写入的暂存目录打包为 `plugins/<pluginId>/…` 区段。
 *
 * 单个文件与整批都有上限：插件数据可能是缓存或大二进制，备份不该因为某个插件把归档撑爆。
 */
internal fun writePluginSections(
    sections: List<PluginBackupArchiveService.Section>,
    pluginArchive: PluginBackupArchiveService,
    writeEntry: (String, ByteArray) -> Unit
) {
    sections.forEach { section ->
        var total = 0L
        for (relative in pluginArchive.listExportFiles(section)) {
            if (!pluginArchive.isValidSectionPath(relative)) continue
            val remaining = MAX_PLUGIN_TOTAL_BYTES - total
            if (remaining == 0L) break
            val limit = minOf(MAX_PLUGIN_FILE_BYTES, remaining)
            val bytes = try {
                pluginArchive.readExportFile(section, relative, limit)
            } catch (e: FileReadTooLargeException) {
                FileLogger.w(TAG, "跳过超过读取上限的插件文件: ${section.pluginId}/$relative (${limit}B)")
                continue
            } catch (e: Exception) {
                continue
            }
            writeEntry("$PLUGIN_ARCHIVE_PREFIX${section.pluginId}/$relative", bytes)
            total += bytes.size
        }
    }
}
