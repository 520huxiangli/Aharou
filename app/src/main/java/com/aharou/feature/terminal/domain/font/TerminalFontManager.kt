package com.aharou.feature.terminal.domain.font

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.res.ResourcesCompat
import com.aharou.R
import com.aharou.core.util.FileLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 一个可用的终端字体：文件绝对路径 + 展示名（文件名去扩展名）。 */
data class TerminalFont(
    val path: String,
    val displayName: String
)

/**
 * 终端自定义字体管理：导入的 ttf/otf 存放在 App 私有目录，按路径加载并缓存 Typeface。
 * 不走 Hilt，设置面板与终端渲染两侧都直接以 Context 调用。
 */
object TerminalFontManager {

    /** 内置字体的伪路径。不是真实文件路径，凡是校验 fontPath 是否存在的地方都要先放行它。 */
    const val BUILTIN_PATH = "builtin:jetbrains-mono-nl"

    private const val DIR_NAME = "terminal_fonts"
    private val SUPPORTED_EXTENSIONS = setOf("ttf", "otf", "ttc")

    private val typefaceCache = ConcurrentHashMap<String, Typeface>()

    fun fontsDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    fun listFonts(context: Context): List<TerminalFont> =
        fontsDir(context).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
            ?.sortedBy { it.name.lowercase() }
            ?.map { TerminalFont(path = it.absolutePath, displayName = it.nameWithoutExtension) }
            ?: emptyList()

    /** 复制字体文件到私有目录并校验可解析；失败返回 null。 */
    fun importFont(context: Context, uri: Uri): TerminalFont? {
        val fileName = queryDisplayName(context, uri) ?: return null
        if (File(fileName).extension.lowercase() !in SUPPORTED_EXTENSIONS) return null

        val target = File(fontsDir(context), fileName)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return null

            // 无效字体文件会在此抛异常，避免把坏文件留在目录里
            Typeface.createFromFile(target)
            typefaceCache.remove(target.absolutePath)
            TerminalFont(path = target.absolutePath, displayName = target.nameWithoutExtension)
        } catch (e: Exception) {
            FileLogger.e("TerminalFont", "导入字体失败: $fileName", e)
            target.delete()
            null
        }
    }

    fun deleteFont(path: String): Boolean {
        typefaceCache.remove(path)
        return File(path).delete()
    }

    /** 加载字体；路径为空或文件损坏时返回 null，由调用方回落到系统等宽字体。 */
    fun loadTypeface(context: Context, path: String): Typeface? {
        if (path.isBlank()) return null
        if (path == BUILTIN_PATH) {
            typefaceCache[BUILTIN_PATH]?.let { return it }
            return runCatching { ResourcesCompat.getFont(context, R.font.jetbrains_mono_nl) }
                .onFailure { FileLogger.e("TerminalFont", "加载内置字体失败", it) }
                .getOrNull()
                ?.also { typefaceCache[BUILTIN_PATH] = it }
        }
        typefaceCache[path]?.let { return it }
        val file = File(path)
        if (!file.isFile) return null
        return try {
            Typeface.createFromFile(file).also { typefaceCache[path] = it }
        } catch (e: Exception) {
            FileLogger.e("TerminalFont", "加载字体失败: $path", e)
            null
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank()) return sanitize(name)
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { sanitize(it) }
    }

    private fun sanitize(name: String): String = name.replace(Regex("[/\\\\:\\s]+"), "_")
}
