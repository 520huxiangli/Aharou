package com.aharou.feature.pet

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.aharou.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 扔给她追的小球：**独立的小悬浮窗** + 简易抛物线物理。
 *
 * 为什么不画在主窗里：主窗只圈住她那么大一块，球飞出去就出界看不见了；给它单开一个小窗
 * （几十 dp 见方），它才能真的从她手里划个弧落到屏幕另一头，而挡住的地方可以忽略。
 * 球是能点的：点一下它会再弹一下。
 */
class PetToy(private val context: Context, private val windowManager: WindowManager) {

    private val density = context.resources.displayMetrics.density
    private val size = dp(TOY_DP)

    private var view: ImageView? = null
    private var params: WindowManager.LayoutParams? = null

    /** 摆件模式打开时，球也不接触摸。 */
    private var touchable = true
    private var x = 0f
    private var y = 0f
    private var vx = 0f
    private var vy = 0f

    /** 落地停稳、等她来捡。 */
    var isResting = false
        private set

    val isActive: Boolean get() = view != null

    /** 停下时的中心横坐标，她要去的就是这儿。 */
    var restingX = 0
        private set

    /** 停稳后回调一次（让她动身去追）。 */
    var onRest: ((Int) -> Unit)? = null

    /** 被捡起来时回调（说句台词、收起球）。 */
    var onPicked: (() -> Unit)? = null

    fun toss(fromCenterX: Int, fromCenterY: Int, towardRight: Boolean) {
        attach()
        x = (fromCenterX - size / 2f).coerceIn(0f, maxX())
        y = (fromCenterY - size / 2f).coerceIn(0f, maxY())
        vx = dp(if (towardRight) 1f else -1f) * dp(Random.nextFloat() * 180f + 320f)
        vy = -dp(Random.nextFloat() * 200f + 820f)
        isResting = false
        move()
    }

    fun update(dt: Float) {
        if (view == null || isResting) return
        vy += dp(GRAVITY_DP) * dt
        x += vx * dt
        y += vy * dt

        if (x <= 0f || x >= maxX()) {
            vx = -vx * BOUNCE
            x = x.coerceIn(0f, maxX())
        }
        if (y >= maxY()) {
            y = maxY()
            vx *= GROUND_FRICTION
            if (abs(vy) < dp(REST_VY_DP)) {
                vy = 0f
                vx = 0f
                isResting = true
                restingX = (x + size / 2f).roundToInt()
                move()
                onRest?.invoke(restingX)
                return
            }
            vy = -vy * BOUNCE
        }
        move()
    }

    /** 点一下球：再弹一下。 */
    private fun hop() {
        if (!isResting) return
        isResting = false
        vy = -dp(Random.nextFloat() * 160f + 620f)
        vx = dp(Random.nextFloat() * 260f - 130f)
    }

    /** 摆件模式下球也不接触摸（跟主窗一起穿）。 */
    fun setTouchable(touchable: Boolean) {
        this.touchable = touchable
        val p = params ?: return
        val v = view ?: return
        p.flags = flagsFor(touchable)
        runCatching { windowManager.updateViewLayout(v, p) }
    }

    fun dismiss() {
        val v = view ?: return
        view = null
        params = null
        isResting = false
        runCatching { windowManager.removeView(v) }
    }

    /** 截屏时先把球藏起来：窗口留着，只是不画（不然会截进画面里）。 */
    fun setWindowHidden(hidden: Boolean) {
        view?.visibility = if (hidden) View.GONE else View.VISIBLE
    }

    private fun attach() {
        if (view != null) return
        val image = ImageView(context).apply {
            setImageResource(R.drawable.ic_pet_ball)
            setOnClickListener { hop() }
        }
        val layoutParams = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        layoutParams.flags = flagsFor(touchable)
        view = image
        params = layoutParams
        runCatching { windowManager.addView(image, layoutParams) }
    }

    private fun move() {
        val p = params ?: return
        val v = view ?: return
        p.x = x.roundToInt()
        p.y = y.roundToInt()
        runCatching { windowManager.updateViewLayout(v, p) }
    }

    /** 捡到球的小动作由她负责，这里只负责让球消失。 */
    fun pick() {
        onPicked?.invoke()
        dismiss()
    }

    private fun flagsFor(touchable: Boolean): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)

    private fun maxX(): Float = screenWidth() - size.toFloat()

    private fun maxY(): Float = screenHeight() * GROUND_RATIO - size

    private fun screenWidth(): Int = context.resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = context.resources.displayMetrics.heightPixels

    private fun dp(value: Float): Float = value * density

    private fun dp(value: Int): Int = (value * density).roundToInt()

    private companion object {
        const val TOY_DP = 26
        const val GRAVITY_DP = 1500f
        const val BOUNCE = 0.45f
        const val GROUND_FRICTION = 0.8f
        const val REST_VY_DP = 90f

        /** 地面在屏幕高度的这个比例处，避开底部导航手势区。 */
        const val GROUND_RATIO = 0.9f
    }
}
