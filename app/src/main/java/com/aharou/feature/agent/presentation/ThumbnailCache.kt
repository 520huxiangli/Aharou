package com.aharou.feature.agent.presentation

import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import com.aharou.core.ui.decodeSampledBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地图片解码结果的进程级 LRU。
 *
 * 聊天列表的附件缩略图与 markdown 正文内嵌图都靠 `produceState` 现解码：LazyColumn 把行回收掉、
 * 用户再滚回来时就会重走一遍读盘 + 采样 + 分配像素。同一张图反复解码是纯重复劳动，故缓存结果。
 *
 * 容量按字节而不是条数算：缩略图（最长边 180px，约 130KB）与正文图（1024px，可达 4MB）相差
 * 三十倍，固定「32 条」在最坏情况下能吃掉一百多 MB，只有按内存预算淘汰才真的兜得住。
 * 被淘汰的 bitmap 不 recycle：它可能还挂在已组合的 ImageBitmap 上，回收会让画面直接崩，交给 GC。
 */
internal object ThumbnailCache {
    private const val MAX_BYTES = 12 * 1024 * 1024

    /** 键带上采样档位：同一路径在缩略图与正文图两档下分辨率不同，混用会把正文图当缩略图用。 */
    private fun key(path: String, maxEdge: Int): String = "$path|$maxEdge"

    private val cache = object : LruCache<String, ImageBitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun get(path: String, maxEdge: Int): ImageBitmap? = cache.get(key(path, maxEdge))

    fun put(path: String, maxEdge: Int, bitmap: ImageBitmap) = cache.put(key(path, maxEdge), bitmap)
}

/**
 * 带缓存地采样解码本地图片：命中直接返回（不切线程），未命中才读盘解码并入缓存。
 *
 * 解码失败不缓存：文件可能稍后才就绪（外部分享刚落盘、远程工作区还在下载），
 * 把 null 也记下来会让图片永远显示不出来。
 */
internal suspend fun decodeSampledBitmapCached(path: String, maxEdge: Int): ImageBitmap? {
    ThumbnailCache.get(path, maxEdge)?.let { return it }
    return withContext(Dispatchers.IO) {
        decodeSampledBitmap(path, maxEdge)?.also { ThumbnailCache.put(path, maxEdge, it) }
    }
}
