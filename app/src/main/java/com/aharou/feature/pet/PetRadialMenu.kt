package com.aharou.feature.pet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 长按头部唤出的径向菜单：围绕角色的头部画一圈圆钮。
 *
 * 自绘而不是用多个子 View，是因为菜单和角色必须待在**同一个悬浮窗**里（开两个窗口会有层级与
 * 焦点问题），所以这里只管「在给定圆心周围画按钮 + 命中测试」，窗口该撑多大由 [PetOverlay] 负责。
 */
class PetRadialMenu(context: Context) : View(context) {

    /** @param active 当前处于开启态的项（麦克风），用醒目色区分。 */
    data class Item(val id: String, val iconRes: Int, val label: String, val active: Boolean = false)

    companion object {
        const val ID_CALL = "call"
        const val ID_SAY = "say"
        const val ID_SLEEP = "sleep"
        const val ID_HIDE = "hide"
        const val ID_TOY = "toy"
        const val ID_PASS = "pass"
        const val ID_SHOT = "shot"
        const val ID_EASTER = "easter"

        /** 菜单文字字号（sp）。 */
        private const val LABEL_SP = 11f

        /**
         * 按钮绕一圈均匀分布，从正上方（-90°）开始顺时针。
         *
         * 数量不写死：加菜单项时不用再改这里（四项目时即上/右/下/左）。
         */
        private fun angleOf(index: Int, count: Int): Float =
            -90f + 360f * index / count.coerceAtLeast(1)
        private const val OPEN_SPEED = 6.5f      // 进度/秒，约 150ms 展开
        private const val ICON_DP = 20f
        private const val BUTTON_R_DP = 21f
        private const val LABEL_GAP_DP = 7f

        private const val COLOR_BG = 0xE6222230.toInt()
        private const val COLOR_BG_ACTIVE = 0xFFE5484D.toInt()
        private const val COLOR_ICON = Color.WHITE
    }

    var onAction: ((String) -> Unit)? = null
    var onDismissed: (() -> Unit)? = null

    private var items: List<Item> = emptyList()
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    private var progress = 0f
    private var target = 0f
    private var lastAnimAt = 0L
    private var pressedIndex = -1

    private val density = resources.displayMetrics.density
    private val buttonRadius = BUTTON_R_DP * density
    private val iconSize = ICON_DP * density
    private val labelGap = LABEL_GAP_DP * density

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        // 字号用 sp 换算，别直接乘已废弃的 scaledDensity（用户改系统字号时要跟着变）。
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            LABEL_SP,
            resources.displayMetrics,
        )
        color = Color.WHITE
        setShadowLayer(3f, 0f, 1f, 0xAA000000.toInt())
    }
    private val iconCache = mutableMapOf<Int, Drawable?>()

    val isOpen: Boolean get() = items.isNotEmpty() && target > 0f

    private val animStep = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val dt = (now - lastAnimAt).coerceAtLeast(8L) / 1000f
            lastAnimAt = now
            progress = if (progress < target) {
                (progress + OPEN_SPEED * dt).coerceAtMost(target)
            } else {
                (progress - OPEN_SPEED * dt).coerceAtLeast(target)
            }
            if (progress == target) {
                if (target == 0f) finishClose()
            } else {
                postOnAnimation(this)
            }
            invalidate()
        }
    }

    fun open(newItems: List<Item>, cx: Float, cy: Float, r: Float) {
        items = newItems
        centerX = cx
        centerY = cy
        radius = r
        progress = 0f
        target = 1f
        pressedIndex = -1
        visibility = VISIBLE
        lastAnimAt = System.currentTimeMillis()
        removeCallbacks(animStep)
        postOnAnimation(animStep)
        invalidate()
    }

    /** 收起：先播完收回动画再清空 items（播完前 [isOpen] 仍为 true）。 */
    fun close() {
        if (items.isEmpty() || target == 0f) return
        target = 0f
        pressedIndex = -1
        lastAnimAt = System.currentTimeMillis()
        removeCallbacks(animStep)
        postOnAnimation(animStep)
    }

    private fun finishClose() {
        items = emptyList()
        visibility = GONE
        invalidate()
        onDismissed?.invoke()
    }

    /** 通话开关这类项的状态会变，展开着的时候要能原地改色。 */
    fun setItemActive(id: String, active: Boolean) {
        if (items.none { it.id == id }) return
        items = items.map { if (it.id == id) it.copy(active = active) else it }
        invalidate()
    }

    // ── 绘制 ────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty() || progress <= 0.01f) return
        val alpha = (255 * progress).toInt().coerceIn(0, 255)

        items.forEachIndexed { index, item ->
            val angle = Math.toRadians(angleOf(index, items.size).toDouble())
            val distance = radius * progress
            val bx = centerX + (cos(angle) * distance).toFloat()
            val by = centerY + (sin(angle) * distance).toFloat()

            circlePaint.style = Paint.Style.FILL
            circlePaint.color = if (item.active) COLOR_BG_ACTIVE else COLOR_BG
            circlePaint.alpha = alpha
            canvas.drawCircle(bx, by, buttonRadius, circlePaint)

            circlePaint.style = Paint.Style.STROKE
            circlePaint.strokeWidth = 1f * density
            circlePaint.color = Color.WHITE
            circlePaint.alpha = (60 * progress).toInt()
            canvas.drawCircle(bx, by, buttonRadius, circlePaint)

            val icon = iconCache.getOrPut(item.iconRes) {
                ContextCompat.getDrawable(context, item.iconRes)?.mutate()
            }
            if (icon != null) {
                val half = iconSize / 2f
                icon.setTint(COLOR_ICON)
                icon.alpha = alpha
                icon.setBounds(
                    (bx - half).toInt(), (by - half).toInt(),
                    (bx + half).toInt(), (by + half).toInt(),
                )
                icon.draw(canvas)
            }

            labelPaint.alpha = (235 * progress).toInt().coerceIn(0, 255)
            canvas.drawText(item.label, bx, by + buttonRadius + labelGap + labelPaint.textSize, labelPaint)
        }
    }

    // ── 交互 ────────────────────────────────────────────────
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isOpen) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedIndex = hitIndex(event.x, event.y)
                if (pressedIndex < 0) {
                    // 点空白处收起
                    close()
                    return true
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val index = hitIndex(event.x, event.y)
                val pressed = pressedIndex
                pressedIndex = -1
                if (index >= 0 && index == pressed) {
                    val id = items[index].id
                    onAction?.invoke(id)
                    return true
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedIndex = -1
                return true
            }
        }
        return true
    }

    private fun hitIndex(x: Float, y: Float): Int {
        items.forEachIndexed { index, _ ->
            val angle = Math.toRadians(angleOf(index, items.size).toDouble())
            val bx = centerX + (cos(angle) * radius).toFloat()
            val by = centerY + (sin(angle) * radius).toFloat()
            if (hypot(x - bx, y - by) <= buttonRadius * 1.35f) return index
        }
        return -1
    }
}
