package com.aharou.feature.plugin.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.aharou.core.util.FileLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一次导出的产物：交给 UI 走系统分享。 */
data class ExportedFile(
    val file: File,
    val mimeType: String,
    val displayName: String,
)

/**
 * 导出产物的落地位置与命名。
 *
 * 落在 `cacheDir/plugin-export`：这些包只是「过一手」的分享中间物，用户自己的技能 / 配置
 * 原样留在 `~/.aharou` 里，没必要再存一份；cacheDir 也在 FileProvider 的 cache-path 覆盖范围内。
 */
internal object ExportFiles {

    private const val DIR_NAME = "plugin-export"

    /**
     * 过期包的保留时长。
     *
     * 分享出去后接收方可能几秒后才真去读文件，分享完立刻删会把刚发出去的包弄坏；所以不在
     * 分享后立刻删，而是下次导出时顺手清掉旧包——目录里不会越攒越多。
     */
    private const val KEEP_MS = 10 * 60 * 1000L

    private val UNSAFE_CHARS = Regex("""[\\/:*?"<>|\p{Cntrl}]""")
    private val WHITESPACE = Regex("\\s+")

    fun prepare(context: Context): File {
        val dir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - KEEP_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
        return dir
    }

    /** 时间戳同时用于文件名与包内 README，一次导出只取一次，免得两边对不上。 */
    fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    fun newFile(
        context: Context,
        prefix: String,
        extension: String,
        stamp: String = timestamp(),
    ): File = File(prepare(context), "$prefix-$stamp.$extension")

    /**
     * 文件名里只留安全字符。
     *
     * MCP 名有 ASCII 校验，技能名没有：中文、空格、甚至 `/` 都可能出现。`/` 会把产物写到别的
     * 目录去，控制字符会让部分分享端认不出文件，所以统一替换掉；中文保留，用户认得出的名字
     * 才有分享价值。
     */
    fun safeName(raw: String): String = raw.trim()
        .replace(UNSAFE_CHARS, "_")
        .replace(WHITESPACE, "_")
        .trim('_', '.')
        .ifBlank { "export" }
        .take(60)
}

/**
 * 把导出产物交给系统分享。
 *
 * 与 `PluginScreenHost` 里那份发布包分享逻辑同形，但那份是私有函数、又不许动它，所以这里
 * 单独写一份：聊天软件 / 网盘 / 邮件都能收，用户怎么转给别人不归我们管。
 */
internal fun shareExportedFile(context: Context, exported: ExportedFile) {
    runCatching {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", exported.file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = exported.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, exported.displayName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // 调用方可能是非 Activity 的 Context（如 Preview），NEW_TASK 兜住这种情况
        context.startActivity(
            Intent.createChooser(send, exported.displayName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        FileLogger.w("ExportShare", "分享导出文件失败：${exported.file.name}（${it.message}）")
    }
}
