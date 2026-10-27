package com.aharou.feature.pet

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.aharou.core.util.FileLogger
import java.io.File

/** 视频素材的尺寸（已经按旋转元数据摆正）。 */
data class VideoProbe(val width: Int, val height: Int) {
    val ratio: Float get() = width.toFloat() / height
}

/** 导入视频素材的结果：UI 按分支给不同提示，别把错误压成一句话。 */
sealed interface VideoImportResult {
    data class Ok(val id: String) : VideoImportResult

    /** 读不出宽高（不是视频、文件坏了、编码器没写元数据）。 */
    data object Unreadable : VideoImportResult

    /** 比例不对：十有八九是普通视频，不是左右拼合素材。 */
    data class BadRatio(val ratio: Float) : VideoImportResult

    data object SaveFailed : VideoImportResult
}

/**
 * 用户自建的桌宠角色：素材放在 `filesDir/pet/<id>/`，与内置角色在 assets 里的目录结构一致。
 *
 * 目录名是 id（ASCII、唯一），显示名另存在目录里的 `name.txt`——这样改名字不会牵动已保存的
 * 角色 id，设置里存的偏好也就不会失效。
 */
object PetUserRoles {

    private const val TAG = "PetUserRoles"

    private const val ROOT = "pet"
    private const val FRONT = "front.png"
    private const val NAME_FILE = "name.txt"
    private const val VIDEOS = "videos"

    /**
     * 素材「整宽 ÷ 高」的合法区间，对应「宽是显示宽两倍」的左右拼合格式。
     * 内置虎鲸娘素材是 1280×360（2 × 16:9）＝ 3.56，按规范做的素材都落在这一档；
     * 1.9~2.1 是 2 × 1:1 的写法（方形角色区），同样按拼合素材接受，只是渲染时会被拉成 16:9。
     */
    private val DOUBLED_WIDE = 3.40f..3.72f
    private val DOUBLED_SQUARE = 1.90f..2.10f

    fun isSupportedRatio(ratio: Float): Boolean =
        ratio in DOUBLED_WIDE || ratio in DOUBLED_SQUARE

    fun root(context: Context): File = File(context.filesDir, ROOT)

    fun dir(context: Context, id: String): File = File(root(context), id)

    /** 扫描用户目录：有 `front.png`，或有 `videos/类别/` 下的 mp4，才算一个能用的角色。 */
    fun list(context: Context): List<PetRole> = runCatching {
        root(context).listFiles().orEmpty()
            .filter { it.isDirectory && isValid(it) }
            .sortedBy { it.name.lowercase() }
            .map { PetRole(it.name, readName(it)) }
    }.getOrDefault(emptyList())

    fun isValid(context: Context, id: String): Boolean =
        id.isSafeId() && runCatching { isValid(dir(context, id)) }.getOrDefault(false)

    private fun isValid(dir: File): Boolean {
        if (File(dir, FRONT).isFile) return true
        val categories = File(dir, VIDEOS).listFiles().orEmpty()
        return categories.any { category ->
            category.isDirectory && category.listFiles().orEmpty().any { it.name.endsWith(".mp4", true) }
        }
    }

    /**
     * 按显示名建一个新的空角色目录，返回它的 id。
     * 重名不会互相覆盖：目录已存在（或撞上内置角色）就往后加序号。
     */
    fun create(context: Context, name: String): String {
        val root = root(context).apply { mkdirs() }
        val builtin = PetAssetStore(context)
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(24)
            .ifEmpty { "user" }
        var id = base
        var index = 2
        while (File(root, id).exists() || builtin.hasEntries("$ROOT/$id")) {
            id = "${base}_$index"
            index++
        }
        val target = File(root, id).apply { mkdirs() }
        writeName(target, name.trim().ifEmpty { id })
        return id
    }

    fun rename(context: Context, id: String, name: String): Boolean {
        if (!isValid(context, id)) return false
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        return runCatching {
            writeName(dir(context, id), trimmed)
            true
        }.getOrElse {
            FileLogger.w(TAG, "重命名失败：$id", it)
            false
        }
    }

    /** 内置角色（assets 里带着素材）不可删。 */
    fun isBuiltin(context: Context, id: String): Boolean {
        if (!id.isSafeId()) return false
        return PetAssetStore(context).hasEntries("$ROOT/$id")
    }

    /** 删掉一个用户角色；内置角色一律拒绝。 */
    fun delete(context: Context, id: String): Boolean {
        if (isBuiltin(context, id) || !isValid(context, id)) return false
        return discard(context, id)
    }

    /** 内部收尾用：删掉一个刚建出来就失败的角色目录，不做有效性判断。 */
    fun discard(context: Context, id: String): Boolean {
        if (!id.isSafeId()) return false
        return runCatching {
            dir(context, id).deleteRecursively()
        }.getOrElse {
            FileLogger.w(TAG, "删除角色失败：$id", it)
            false
        }
    }

    /** 用户选的文件名（去掉扩展名），用来当角色的显示名；读不到就返回 null。 */
    fun pickedName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                cursor.getString(0)
            }
    }.getOrNull()?.substringBeforeLast('.')

    fun probeVideo(context: Context, uri: Uri): VideoProbe? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull()
            if (width == null || height == null || width <= 0 || height <= 0) return null
            // 竖着拍的视频会在元数据里带旋转，按摆正后的比例判才准
            val rotated = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull()?.let { it == 90 || it == 270 } == true
            if (rotated) VideoProbe(height, width) else VideoProbe(width, height)
        } catch (e: Exception) {
            FileLogger.w(TAG, "读视频尺寸失败：$uri", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 校验并落盘成 `pet/<id>/videos/idle/<文件名>.mp4`。 */
    fun importVideo(context: Context, uri: Uri, name: String): VideoImportResult {
        val probe = probeVideo(context, uri) ?: return VideoImportResult.Unreadable
        if (!isSupportedRatio(probe.ratio)) return VideoImportResult.BadRatio(probe.ratio)
        val id = create(context, name)
        val fileName = name.trim().ifEmpty { "idle" }.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".mp4"
        val folder = File(dir(context, id), "$VIDEOS/idle").apply { mkdirs() }
        val saved = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                File(folder, fileName).outputStream().use { input.copyTo(it) }
            } != null
        }.getOrElse {
            FileLogger.w(TAG, "保存视频素材失败：$uri", it)
            false
        }
        if (!saved) {
            discard(context, id)
            return VideoImportResult.SaveFailed
        }
        return VideoImportResult.Ok(id)
    }

    private fun readName(dir: File): String =
        File(dir, NAME_FILE).takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
            ?: dir.name

    private fun writeName(dir: File, name: String) {
        dir.mkdirs()
        File(dir, NAME_FILE).writeText(name)
    }

    /** 目录名只允许简单字符，避免 `..` 之类的路径穿越。 */
    private fun String.isSafeId(): Boolean =
        isNotBlank() && '/' !in this && '\\' !in this && this != "." && this != ".."
}
