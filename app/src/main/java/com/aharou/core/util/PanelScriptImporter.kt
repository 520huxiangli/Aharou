package com.aharou.core.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.aharou.feature.agent.domain.container.ContainerInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 面板脚本导入：把手机上的脚本文件拷进 `~/.aharou/scripts/`。
 *
 * 两个入口共用——外部分享（ACTION_SEND）与设置页「选择脚本」面板里的从文件导入。
 */
object PanelScriptImporter {

    /** 认作面板脚本的扩展名；其余文件按普通附件处理。 */
    private val EXTENSIONS = setOf("sh", "py", "js")

    fun isPanelScript(name: String?): Boolean =
        name?.substringAfterLast('.', "")?.lowercase() in EXTENSIONS

    /** 读 provider 的显示名：`file://` 取路径末段，其余走 OpenableColumns。 */
    fun queryDisplayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
    }

    /** `~/.aharou/scripts/` 对应的宿主目录（容器内即 `/root/.aharou/scripts`）。 */
    fun scriptsDir(context: Context): File = File(ContainerInstaller.migrateLegacyDir(context), "scripts")

    /** 拷进脚本目录，返回落盘文件名；失败返回 null。 */
    suspend fun import(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val name = queryDisplayName(context, uri) ?: return@withContext null
        runCatching {
            val dir = scriptsDir(context).apply { mkdirs() }
            val dest = File(dir, name)
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext null
            dest.name
        }.onFailure { FileLogger.w("PanelScriptImporter", "脚本导入失败：$uri", it) }.getOrNull()
    }
}
