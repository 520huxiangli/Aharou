package com.aharou.feature.overlay

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
import com.aharou.MainActivity
import kotlin.math.abs

/**
 * 悬浮窗（自 上游项目 的 ToolOverlayController 移植·精简）。
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
        /** 头像可点区域宽度 = 左内边距 + 头像 + 一点余量。 */
        private const val ICON_HIT_WIDTH_DP = 44

        /** 右端下拉箭头的可点宽度。 */
        private const val ARROW_HIT_WIDTH_DP = 28
        /** 展开后文字框的高度上限（估位置用，实际由内容撑开）。 */
        private const val EXPANDED_BOX_MAX_DP = 150
        private const val EXPANDED_MAX_LINES = 6
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
    private var logoView: ImageView? = null

    /** 胶囊右端的展开箭头：收起时朝下（提示可下拉），展开时朝上。仅可展开时可见。 */
    private var arrowView: TextView? = null

    /** 胶囊下方那块独立的台词框：展开时出现，收起时不占位。 */
    private var expandedView: TextView? = null

    /** 点头像时要干什么（通话中 = 开关通话）。为空则点头像等同点胶囊（回 App）。 */
    private var iconAction: (() -> Unit)? = null

    /** 通话中才能展开：一行装不下它说的话，点一下摊开看完整。 */
    private var expandable = false
    private var expanded = false
    private var layoutParams: WindowManager.LayoutParams? = null

    @Volatile
    var isShown: Boolean = false
        private set

    fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /**
     * @param iconAction 点头像时的动作（通话中传通话开关）；为空则点头像等同点胶囊
     * @param callActive 通话中：头像加一圈亮环，一眼能看出在听
     * @param expandable 点胶囊能否展开看完整内容（通话中开）
     */
    fun show(
        toolName: String,
        statusText: String,
        iconAction: (() -> Unit)? = null,
        callActive: Boolean = false,
        expandable: Boolean = false,
    ) {
        if (!hasOverlayPermission()) {
            if (isShown) hide()
            return
        }
        this.iconAction = iconAction
        this.expandable = expandable
        mainHandler.post {
            try {
                if (view == null) attach()
                val title = toolName.ifBlank { "Aharou" }
                val status = statusText.ifBlank { "执行中…" }
                if (titleView?.text?.toString() != title) titleView?.text = title
                if (statusView?.text?.toString() != status) statusView?.text = status
                if (expandedView?.text?.toString() != status) expandedView?.text = status
                logoView?.background = if (callActive) callRingDrawable() else null
                arrowView?.visibility = if (expandable) View.VISIBLE else View.GONE
                // 收起条件：不再可展开，或者展开时它已经没在说话了（念完就该缩回去）
                if (expanded && (!expandable || status.isBlank())) setExpanded(false)
            } catch (_: Throwable) {
            }
        }
    }

    private fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        arrowView?.text = if (value) "\u25B4" else "\u25BE"
        expandedView?.visibility = if (value) View.VISIBLE else View.GONE
        val params = layoutParams ?: return
        // 展开后整体变高：贴底时会被屏幕截掉，先按估算高度把它提上来
        if (value) {
            val approx = dpToPx(CAPSULE_HEIGHT_DP + EXPANDED_BOX_MAX_DP + 6)
            val limit = context.resources.displayMetrics.heightPixels - approx
            if (params.y > limit) params.y = limit.coerceAtLeast(0)
        }
        view?.let { runCatching { windowManager.updateViewLayout(it, params) } }
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
            logoView = null
            arrowView = null
            expandedView = null
            iconAction = null
            expanded = false
            expandable = false
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
            WindowManager.LayoutParams.WRAP_CONTENT,
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
        // 外层竖向：上面是胶囊行（固定高），下面挂展开的文字框
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
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
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(CAPSULE_HEIGHT_DP)
            )
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
        logoView = logo
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
        // 展开箭头：贴在胶囊右端。点击它等同于点胶囊（都在头像区右侧，走同一分支）。
        val arrow = TextView(context).apply {
            setTextColor(Color.argb(170, 255, 255, 255))
            textSize = 10f
            text = "\u25BE"
            gravity = Gravity.CENTER
            visibility = if (expandable) View.VISIBLE else View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dpToPx(6) }
        }
        arrowView = arrow
        container.addView(arrow)
        root.addView(container)
        // 展开台词框：贴在胶囊下方，独立外观（圆角、自带内边距），收起时不占位
        val box = TextView(context).apply {
            setTextColor(Color.argb(235, 255, 255, 255))
            textSize = 12f
            maxLines = EXPANDED_MAX_LINES
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.argb(235, 28, 28, 30))
                setStroke(dpToPx(1), Color.argb(40, 255, 255, 255))
            }
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(6)
                // 左边缘对齐到标题/状态那一列（让开头像与文字列左内边距），不跨到头像下面
                marginStart = dpToPx(8 + LOGO_SIZE_DP + 8)
            }
        }
        expandedView = box
        root.addView(box)
        return root
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
                    when {
                        dragging -> prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
                        // 右端下拉箭头：展开/收起回合（只有通话中才有回合）
                        expandable && event.x >= view.width - dpToPx(ARROW_HIT_WIDTH_DP) ->
                            setExpanded(!expanded)

                        // 落在头像上：切换语音（开 / 停）
                        event.x <= dpToPx(ICON_HIT_WIDTH_DP) ->
                            iconAction?.invoke() ?: bringAppToFront()

                        // 状态区：回到 App
                        else -> bringAppToFront()
                    }
                    true
                }

                else -> false
            }
        }
    }

    /** 通话中头像的亮环：原生 View 拿不到 Compose 主题色，沿用本文件的直写色风格。 */
    private fun callRingDrawable(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        setStroke(dpToPx(2), Color.argb(255, 102, 187, 106))
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
