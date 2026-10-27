package com.aharou.feature.pet

import android.content.Context
import android.media.MediaPlayer
import com.aharou.core.util.FileLogger
import java.io.File
import java.io.InputStream

/** 视频素材的两种来源：内置的只能走 assets 的 fd，用户导入的是普通文件。 */
sealed interface PetVideoSource {
    data class Asset(val path: String) : PetVideoSource

    data class PlainFile(val path: String) : PetVideoSource
}

/**
 * 桌宠素材访问层。
 *
 * 路径一律沿用打包时的写法（`pet/<role>/front.png`）：先在 `filesDir/pet/<role>/...` 里找用户
 * 导入的素材，没命中的再回退到 assets 里的内置素材。这样用户自建角色与内置角色共用同一套读取
 * 代码，渲染层不用关心素材从哪来。
 */
class PetAssetStore(private val context: Context) {

    /** 把素材路径映射到用户目录里的实际文件（不一定存在）。 */
    fun userFile(path: String): File = File(context.filesDir, path)

    fun open(path: String): InputStream {
        val file = userFile(path)
        if (file.isFile) return file.inputStream()
        return context.assets.open(path)
    }

    /** 目录里的条目名：用户目录优先，内置目录补上它没有的。 */
    fun list(dir: String): List<String> {
        val user = userFile(dir).list()?.toList().orEmpty()
        val builtin = runCatching { context.assets.list(dir).orEmpty().toList() }
            .getOrDefault(emptyList())
        return user + builtin.filterNot { it in user }
    }

    fun exists(path: String): Boolean = userFile(path).isFile || assetExists(path)

    /** 目录里有没有条目（用户目录与 assets 任一命中即可）。 */
    fun hasEntries(dir: String): Boolean = list(dir).isNotEmpty()

    fun videoSource(path: String): PetVideoSource? {
        val file = userFile(path)
        if (file.isFile) return PetVideoSource.PlainFile(file.absolutePath)
        return if (assetExists(path)) PetVideoSource.Asset(path) else null
    }

    /** 让播放器读这段素材；素材不存在或打不开时返回 false。 */
    fun attach(player: MediaPlayer, path: String): Boolean {
        val ok = when (val source = videoSource(path)) {
            is PetVideoSource.Asset -> runCatching {
                context.assets.openFd(source.path).use { fd ->
                    player.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                }
            }.isSuccess
            is PetVideoSource.PlainFile -> runCatching {
                player.setDataSource(source.path)
            }.isSuccess
            null -> false
        }
        if (!ok) FileLogger.w(TAG, "桌宠素材打不开：$path")
        return ok
    }

    /** assets 里有没有这个条目：文件用 open 探，目录用 list 探。 */
    private fun assetExists(path: String): Boolean =
        runCatching { context.assets.open(path).use { } }.isSuccess ||
            runCatching { context.assets.list(path).orEmpty().isNotEmpty() }.getOrDefault(false)

    private companion object {
        const val TAG = "PetAssetStore"
    }
}
