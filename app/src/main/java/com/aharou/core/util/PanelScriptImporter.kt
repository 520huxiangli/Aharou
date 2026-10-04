package com.aharou.core.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.aharou.feature.agent.domain.container.ContainerInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** 面板分享包：脚本源码 + 该提供商的面板 DIY 参数。 */
data class PanelPackage(
    val scriptName: String,
    val script: String,
    val params: Map<String, String>
)

/**
 * 面板脚本导入/导出：把手机上的脚本文件拷进 `~/.aharou/scripts/`，
 * 或把某个脚本连同它的 DIY 参数打包成可分享的 JSON。
 *
 * 导入有两个入口——外部分享（ACTION_SEND）与设置页「选择脚本」面板里的从文件导入。
 */
object PanelScriptImporter {

    /** 认作面板脚本的扩展名；其余文件按普通附件处理。 */
    private val EXTENSIONS = setOf("sh", "py", "js")

    /** 分享包文件名后缀，接收方据此辨识这是面板脚本包。 */
    const val PACKAGE_SUFFIX = ".aharou-panel.json"

    private const val PACKAGE_TYPE = "aharou.panel"

    fun isPanelScript(name: String?): Boolean =
        name?.substringAfterLast('.', "")?.lowercase() in EXTENSIONS

    /** 是否为可能的面板分享包（按扩展名初筛，真正识别靠解析内容）。 */
    fun maybePanelPackage(name: String?): Boolean =
        name?.lowercase()?.endsWith(".json") == true

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

    /**
     * 把脚本连同 DIY 参数打包成 JSON 写进缓存目录，返回可分享的 FileProvider Uri。
     * 脚本文件不存在时返回 null。每次导出前清空缓存目录，避免临时文件堆积。
     */
    fun exportPackage(context: Context, scriptName: String, params: Map<String, String>): Uri? {
        val name = File(scriptName).name
        val scriptFile = File(scriptsDir(context), name)
        if (!scriptFile.isFile) return null
        return runCatching {
            val json = JSONObject().apply {
                put("type", PACKAGE_TYPE)
                put("version", 1)
                put("scriptName", name)
                put("script", scriptFile.readText())
                put("params", JSONObject(params))
            }
            val dir = File(context.cacheDir, "panel-share").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, name + PACKAGE_SUFFIX)
            out.writeText(json.toString(2))
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", out)
        }.onFailure { FileLogger.w("PanelScriptImporter", "面板导出失败：$scriptName", it) }.getOrNull()
    }

    /** 解析面板分享包；不是本格式（或内容不合法）返回 null。 */
    suspend fun parsePackage(context: Context, uri: Uri): PanelPackage? = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().decodeToString() }
                ?: return@runCatching null
            val json = JSONObject(text)
            if (json.optString("type") != PACKAGE_TYPE) return@runCatching null
            val name = File(json.optString("scriptName")).name
            val script = json.optString("script")
            if (name.isBlank() || script.isBlank()) return@runCatching null
            val paramsObj = json.optJSONObject("params")
            val params = buildMap {
                paramsObj?.keys()?.forEach { key -> put(key, paramsObj.optString(key)) }
            }
            PanelPackage(name, script, params)
        }.getOrNull()
    }

    /** 把分享包里的脚本落盘到脚本目录，返回文件名；失败返回 null。 */
    suspend fun writePackageScript(context: Context, pkg: PanelPackage): String? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = scriptsDir(context).apply { mkdirs() }
            val dest = File(dir, pkg.scriptName)
            dest.writeText(pkg.script)
            dest.name
        }.onFailure { FileLogger.w("PanelScriptImporter", "面板包导入失败：${pkg.scriptName}", it) }.getOrNull()
    }
}
