package com.aharou.feature.pet

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.aharou.core.util.FileLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

/**
 * 灵动岛：屏幕顶部一枚胶囊悬浮窗，显示 emoji + 自定义文本 + 时间 + 状态点。
 *
 * 做法搬自 dsh-pet（MIT）：拖动移动，松手吸到顶部（24dp 阈值），位置记忆，单击切换桌宠显隐。
 * 用传统 View 而不是 Compose——桌宠那套悬浮窗本来就是 View 体系，少一层宿主搭桥。
 */
class PetIsland(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private val prefs = context.getSharedPreferences(PetOverlay.PREFS, Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var textView: TextView? = null
    private var timeView: TextView? = null
    private var shown = false

    /** 单击胶囊：切换桌宠显示/隐藏（由服务接上）。 */
    var onTogglePet: (() -> Unit)? = null

    val isShowing: Boolean get() = shown && root != null

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val ticker = object : Runnable {
        override fun run() {
            updateTime()
            main.postDelayed(this, TIME_TICK_MS)
        }
    }

    fun show() {
        if (isShowing) return
        try {
            val bounds = petScreenBounds(wm, context)
            val width = (bounds.width() * WIDTH_RATIO).toInt().coerceAtLeast((180 * density).toInt())
            val height = (HEIGHT_DP * density).toInt()

            val emoji = TextView(context).apply {
                text = EMOJI
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            }
            // 名字别叫 text：局部变量会遮住 apply 里 receiver 的 text 属性
            val label = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(FG)
                maxLines = 1
                visibility = View.GONE
            }
            val time = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(FG)
                text = timeFormat.format(Date())
            }
            val dot = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(DOT)
                }
            }

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = height / 2f
                    setColor(BG)
                }
                val pad = (14 * density).toInt()
                setPadding(pad, 0, pad, 0)
                addView(emoji)
                addView(label, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = (8 * density).toInt() })
                // 撑开的空白：文本隐藏时时间也还是靠右
                addView(View(context), LinearLayout.LayoutParams(0, 1).apply { weight = 1f })
                addView(time)
                addView(dot, LinearLayout.LayoutParams((8 * density).toInt(), (8 * density).toInt()).apply { leftMargin = (8 * density).toInt() })
            }

            val savedX = prefs.getInt(KEY_ISLAND_X, -1)
            val savedY = prefs.getInt(KEY_ISLAND_Y, -1)
            val lp = WindowManager.LayoutParams(
                width,
                height,
                petOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = if (savedX >= 0) savedX else (bounds.width() - width) / 2
                // 有记忆位置也压在顶部之下一点，吸顶后正常就该在这个高度
                y = if (savedY >= 0) savedY.coerceAtLeast((MIN_TOP_DP * density).toInt())
                else (12 * density).toInt()
            }

            var downX = 0f
            var downY = 0f
            var lastX = 0f
            var lastY = 0f
            var moved = false
            row.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX
                        downY = ev.rawY
                        lastX = ev.rawX
                        lastY = ev.rawY
                        moved = false
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (moved || hypot(ev.rawX - downX, ev.rawY - downY) > DRAG_SLOP_DP * density) {
                            moved = true
                            drag(ev.rawX - lastX, ev.rawY - lastY)
                        }
                        lastX = ev.rawX
                        lastY = ev.rawY
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) snapAndSave() else onTogglePet?.invoke()
                        true
                    }
                    else -> false
                }
            }

            root = row
            params = lp
            textView = label
            timeView = time
            wm.addView(row, lp)
            shown = true
            main.removeCallbacks(ticker)
            main.post(ticker)
        } catch (e: Throwable) {
            FileLogger.w(TAG, "灵动岛创建失败", e)
            root = null
            params = null
            shown = false
        }
    }

    fun dismiss() {
        shown = false
        main.removeCallbacks(ticker)
        params?.let { p -> prefs.edit().putInt(KEY_ISLAND_X, p.x).putInt(KEY_ISLAND_Y, p.y).apply() }
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        params = null
        textView = null
        timeView = null
    }

    /** 改胶囊里的自定义文本；空串就只留 emoji 与时间。 */
    fun setText(text: String) {
        main.post {
            val view = textView ?: return@post
            view.text = text
            view.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun drag(dx: Float, dy: Float) {
        val p = params ?: return
        val v = root ?: return
        p.x = (p.x + dx.toInt()).coerceAtLeast(0)
        p.y = (p.y + dy.toInt()).coerceAtLeast(0)
        runCatching { wm.updateViewLayout(v, p) }
    }

    /** 松手时贴近顶部就吸上去，并把位置记下来。 */
    private fun snapAndSave() {
        val p = params ?: return
        val v = root ?: return
        if (p.y < SNAP_THRESHOLD_DP * density) p.y = (MIN_TOP_DP * density).toInt()
        runCatching { wm.updateViewLayout(v, p) }
        prefs.edit().putInt(KEY_ISLAND_X, p.x).putInt(KEY_ISLAND_Y, p.y).apply()
    }

    private fun updateTime() {
        timeView?.text = timeFormat.format(Date())
    }

    companion object {
        private const val TAG = "PetIsland"

        /** 胶囊宽度占屏宽比例与高度（dp），沿用 dsh-pet 的默认观感。 */
        private const val WIDTH_RATIO = 0.5f
        private const val HEIGHT_DP = 54

        private const val EMOJI = "🐳"
        private const val BG = 0xF21C1E24.toInt()
        private const val FG = 0xFFEAEAF0.toInt()
        private const val DOT = 0xFF34C759.toInt()

        private const val DRAG_SLOP_DP = 6f
        private const val SNAP_THRESHOLD_DP = 24f
        private const val MIN_TOP_DP = 4
        private const val TIME_TICK_MS = 30_000L

        private const val KEY_ISLAND_X = "island_x"
        private const val KEY_ISLAND_Y = "island_y"

        /** 灵动岛开关，跟桌宠设置放同一个 prefs。 */
        const val KEY_ISLAND_ENABLED = "island_enabled"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PetOverlay.PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ISLAND_ENABLED, false)

        fun writeEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PetOverlay.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ISLAND_ENABLED, enabled).apply()
        }
    }
}

private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
