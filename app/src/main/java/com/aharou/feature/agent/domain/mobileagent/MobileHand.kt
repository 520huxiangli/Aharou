package com.aharou.feature.agent.domain.mobileagent

import android.graphics.Bitmap
import com.aharou.feature.agent.domain.model.AgentImage
import kotlinx.serialization.json.JsonElement
import java.security.MessageDigest

/**
 * 手机操作子代理的「一只手」：能看屏、能动手。
 *
 * 循环只依赖这个接口，不关心背后是影子屏还是无障碍——让 VdTool / A11yTool 各自实现即可，
 * 于是 [MobileOperator] 不必反向依赖工具实例，避免 DI 循环。
 */
interface MobileHand {
    /**
     * 本手是否会读无障碍节点树（同一屏空间里的可选第二输入）。影子屏没有树 → false。
     */
    val readsNodeTree: Boolean get() = false

    /**
     * 循环开始前的准备：确保这只手可用（影子屏在跑），必要时把目标 App 打开。
     * 返回错误说明文本，null 表示就绪。
     */
    suspend fun prepare(component: String?): String?

    /** 看一次屏：截图（VdTool 已带坐标网格）+ 一行结构化 caption；有树时附节点行。 */
    suspend fun observe(): HandObservation

    /**
     * 只取一次界面指纹（不产出给模型看的截图，比 [observe] 便宜），供「动作后等界面静默」轮询。
     * 这只手给不出指纹时返回 null——循环会退化成「动作后直接重看」，不算失败。
     */
    suspend fun fingerprint(): ScreenFingerprint? = null

    /** 下落一个动作。失败以 [HandActResult.ok]=false 表达，不抛异常。 */
    suspend fun act(args: Map<String, JsonElement>): HandActResult
}

/**
 * 一次观测。[image] 为空且 [error] 非空表示这一屏没看到；[nodesText] 是可选第二输入。
 */
data class HandObservation(
    val caption: String,
    val image: AgentImage?,
    val nodesText: String? = null,
    val error: String? = null,
    /** 这一屏的像素宽高（归一化坐标换算用）；拿不到为 0。 */
    val width: Int = 0,
    val height: Int = 0,
    /** 这一屏的界面指纹；拿不到为 null。 */
    val fingerprint: ScreenFingerprint? = null,
)

/** 一次动作的结果（失败不抛异常，用 ok=false + 说明表达）。 */
data class HandActResult(val ok: Boolean, val message: String)

/**
 * 一屏的轻量指纹：画面内容哈希（截图降采样后量化再哈希）+ 该屏像素尺寸，有节点树的手还可以带上
 * 前台包名与 Activity。不存整张图，只为了让循环判断「动作之后屏幕到底变了没有」「同动作是不是
 * 还停在同一屏」，以及把前后两屏写进 trace。
 *
 * @param contentHash 内容哈希（[GRID]×[GRID] 灰度 5bit 量化后取 MD5 前 6 字节的十六进制）
 * @param width / [height] 该屏的像素宽高（与动作下发用的像素坐标系一致）
 * @param packageName / [activity] 前台应用；拿不到时为 null（轮询指纹时不查前台，省一次 dumpsys）
 */
data class ScreenFingerprint(
    val contentHash: String,
    val width: Int = 0,
    val height: Int = 0,
    val packageName: String? = null,
    val activity: String? = null,
) {
    /**
     * 是不是「同一块屏上的同一个画面」。包名/Activity 只在两边都拿到时才比较——
     * 观测时查得到、轮询时查不到是常态，不能因此把同一屏判成变了。
     */
    fun sameContent(other: ScreenFingerprint?): Boolean {
        if (other == null) return false
        if (contentHash != other.contentHash) return false
        if (width != other.width || height != other.height) return false
        if (packageName != null && other.packageName != null && packageName != other.packageName) return false
        if (activity != null && other.activity != null && activity != other.activity) return false
        return true
    }

    /** trace 里的一行短表示：`pkg/Act #hash 1080x1920`（缺的字段略去）。 */
    fun render(): String = buildString {
        val screen = listOfNotNull(
            packageName?.takeIf { it.isNotBlank() },
            activity?.takeIf { it.isNotBlank() },
        ).joinToString("/")
        if (screen.isNotEmpty()) append(screen).append(' ')
        append('#').append(contentHash)
        if (width > 0 && height > 0) append(' ').append(width).append('x').append(height)
    }

    companion object {
        /** 降采样边长：24×24 足够看出整屏变化，又会把状态栏时钟这种小字抹平，不至于永远「没静下来」。 */
        private const val GRID = 24

        /** 从一张整屏截图算指纹；[bitmap] 为空或尺寸无效时返回 null。 */
        fun fromBitmap(bitmap: Bitmap?, packageName: String? = null, activity: String? = null): ScreenFingerprint? {
            if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0) return null
            val thumb = if (bitmap.width == GRID && bitmap.height == GRID) bitmap
            else Bitmap.createScaledBitmap(bitmap, GRID, GRID, true)
            val pixels = IntArray(GRID * GRID)
            thumb.getPixels(pixels, 0, GRID, 0, 0, GRID, GRID)
            if (thumb !== bitmap) thumb.recycle()
            val digest = MessageDigest.getInstance("MD5")
            for (pixel in pixels) {
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                // 亮度再量化到 5 bit：抖动与极小的像素差异不该被当成「界面变了」。
                digest.update((((r * 299 + g * 587 + b * 114) / 1000) shr 3).toByte())
            }
            val hash = digest.digest().take(6).joinToString("") { "%02x".format(it) }
            return ScreenFingerprint(hash, bitmap.width, bitmap.height, packageName, activity)
        }
    }
}
