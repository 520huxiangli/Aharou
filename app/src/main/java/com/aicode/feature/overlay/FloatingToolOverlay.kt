package com.aicode.feature.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aicode.MainActivity
import kotlin.math.abs

/**
 * 悬浮窗（自 OpenMinis 的 ToolOverlayController 移植·精简）。
 *
 * 一枚可拖拽的胶囊：App 图标 + 工具名 + 状态短文本；点击回到 App；拖拽移动（位置持久化）。
 * TYPE_APPLICATION_OVERLAY（需 SYSTEM_ALERT_WINDOW 授权），由 [FloatingToolService] 持有与驱动显隐。
 */
class FloatingToolOverlay(private val context: Context) {

    companion object {
        private const val PREFS = "aharou_overlay_prefs"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val DRAG_SLOP_DP = 8
        private const val CAPSULE_HEIGHT_DP = 44
        private const val LOGO_SIZE_DP = 26
        private const val EDGE_PADDING_DP = 10
        private const val CAPSULE_WIDTH_FRACTION = 0.5f
        private const val CAPSULE_WIDTH_FLOOR_DP = 170
        private const val CAPSULE_WIDTH_CAP_DP = 400
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var view: View? = null
    private var titleView: TextView? = null
    private var statusView: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    @Volatile
    var isShown: Boolean = false
        private set

    fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun show(toolName: String, statusText: String) {
        if (!hasOverlayPermission()) {
            if (isShown) hide()
            return
        }
        mainHandler.post {
            try {
                if (view == null) attach()
                titleView?.text = toolName.ifBlank { "Aharou" }
                statusView?.text = statusText.ifBlank { "执行中…" }
            } catch (_: Throwable) {
            }
        }
    }

    fun hide() {
        mainHandler.post {
            val v = view ?: return@post
            try {
                windowManager.removeView(v)
            } catch (_: Throwable) {
            }
            view = null
            titleView = null
            statusView = null
            layoutParams = null
            isShown = false
        }
    }

    private fun attach() {
        val container = buildView()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            fixedCapsuleWidthPx(),
            dpToPx(CAPSULE_HEIGHT_DP),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val savedX = prefs.getInt(KEY_X, -1)
            val savedY = prefs.getInt(KEY_Y, -1)
            if (savedX >= 0 && savedY >= 0) {
                x = savedX
                y = savedY
            } else {
                val metrics = context.resources.displayMetrics
                x = dpToPx(EDGE_PADDING_DP)
                y = metrics.heightPixels - dpToPx(CAPSULE_HEIGHT_DP + 48 + EDGE_PADDING_DP)
            }
        }
        layoutParams = params
        attachTouchListener(container, params)
        windowManager.addView(container, params)
        view = container
        isShown = true
    }

    private fun buildView(): View {
        val density = context.resources.displayMetrics.density
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(8), dpToPx(6), dpToPx(12), dpToPx(6))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = (CAPSULE_HEIGHT_DP / 2f) * density
                setColor(Color.argb(235, 28, 28, 30))
                setStroke(dpToPx(1), Color.argb(40, 255, 255, 255))
            }
            elevation = 8f * density
            gravity = Gravity.CENTER_VERTICAL
        }
        // 应用图标（裁剪为圆形由容器承担近似效果；用圆形背景兜底）
        val logo = ImageView(context).apply {
            val size = dpToPx(LOGO_SIZE_DP)
            layoutParams = LinearLayout.LayoutParams(size, size)
            runCatching {
                setImageDrawable(context.packageManager.getApplicationIcon(context.packageName))
            }
        }
        container.addView(logo)
        val textColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dpToPx(8), 0, 0, 0)
        }
        titleView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = "Aharou"
        }
        statusView = TextView(context).apply {
            setTextColor(Color.argb(180, 255, 255, 255))
            textSize = 10f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = "执行中…"
        }
        textColumn.addView(titleView)
        textColumn.addView(statusView)
        container.addView(textColumn)
        return container
    }

    private fun attachTouchListener(view: View, params: WindowManager.LayoutParams) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && abs(dx) + abs(dy) > dpToPx(DRAG_SLOP_DP).toFloat()) dragging = true
                    if (dragging) {
                        params.x = (startX + dx).toInt()
                        params.y = (startY + dy).toInt()
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                    } else {
                        bringAppToFront()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun bringAppToFront() {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    private fun fixedCapsuleWidthPx(): Int {
        val metrics = context.resources.displayMetrics
        val fraction = (metrics.widthPixels * CAPSULE_WIDTH_FRACTION).toInt()
        val floor = dpToPx(CAPSULE_WIDTH_FLOOR_DP)
        val cap = minOf((metrics.widthPixels * 0.7f).toInt(), dpToPx(CAPSULE_WIDTH_CAP_DP))
        return fraction.coerceIn(floor, maxOf(floor, cap))
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}
