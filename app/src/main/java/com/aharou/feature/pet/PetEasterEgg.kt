package com.aharou.feature.pet

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import com.aharou.core.util.FileLogger
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 桌宠彩蛋弹窗：从角色素材池随机挑一张图，随机位置盖在屏幕上，点一下就关。
 *
 * 做法搬自 dsh-pet（MIT），素材放在角色目录的 `easter/` 下。可以同时开好几张互不影响——
 * 它是装饰，任何一步出错都只记日志，绝不往外抛。
 */
class PetEasterEgg private constructor(
    private val context: Context,
    private val assetPath: String,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val view: ImageView
    private val params: WindowManager.LayoutParams

    init {
        val bitmap = runCatching {
            context.assets.open(assetPath).use { BitmapFactory.decodeStream(it) }
        }.getOrNull()

        view = ImageView(context).apply {
            setImageBitmap(bitmap)
            setOnClickListener { dismiss() }
            adjustViewBounds = true
        }

        val density = context.resources.displayMetrics.density
        val bounds = petScreenBounds(wm, context)
        // 彩蛋图最大占 60% 屏宽（素材本身已按这个上限缩过，这里再兜一道）
        val maxWidth = (bounds.width() * MAX_WIDTH_RATIO).toInt()
        val width: Int
        val height: Int
        if (bitmap != null && bitmap.width > maxWidth) {
            width = maxWidth
            height = (bitmap.height * (maxWidth.toFloat() / bitmap.width)).roundToInt()
        } else {
            width = bitmap?.width ?: (300 * density).toInt()
            height = bitmap?.height ?: (300 * density).toInt()
        }

        // 随机落点：窗口比屏幕还大时区间为空，nextInt 会崩，先夹一道
        val rangeX = (bounds.width() - width).coerceAtLeast(0)
        val rangeY = (bounds.height() - height).coerceAtLeast(0)
        params = WindowManager.LayoutParams(
            width,
            height,
            petOverlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (rangeX > 0) Random.nextInt(rangeX) else 0
            y = if (rangeY > 0) Random.nextInt(rangeY) else 0
        }
    }

    fun show() {
        runCatching { wm.addView(view, params) }
            .onFailure { FileLogger.w(TAG, "彩蛋弹窗显示失败：$assetPath", it) }
    }

    fun dismiss() {
        runCatching { wm.removeView(view) }
    }

    companion object {
        private const val TAG = "PetEasterEgg"
        private const val MAX_WIDTH_RATIO = 0.6f

        /** 角色目录下的彩蛋素材目录。 */
        fun assetDir(role: String): String = "pet/$role/easter"

        /** 这个角色带了几张彩蛋图（0 表示没素材，菜单项该藏起来）。 */
        fun pool(context: Context, role: String): List<String> = runCatching {
            context.assets.list(assetDir(role)).orEmpty()
                .filter { it.endsWith(".jpg", true) || it.endsWith(".png", true) }
                .sorted()
        }.getOrDefault(emptyList())

        /** 随机弹一张；没素材就不动。返回是否真的弹了。 */
        fun showRandom(context: Context, role: String): Boolean {
            val images = pool(context, role)
            if (images.isEmpty()) return false
            return runCatching {
                PetEasterEgg(context, "${assetDir(role)}/${images[Random.nextInt(images.size)]}").show()
                true
            }.getOrElse {
                FileLogger.w(TAG, "彩蛋弹窗失败", it)
                false
            }
        }
    }
}
