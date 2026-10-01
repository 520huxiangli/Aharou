package com.aharou.feature.pet

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aharou.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * 桌宠悬浮窗：用三视图（正面 / 侧面 / 背面）拼出的小染，盖在其它 App 之上。
 *
 * 三张图各有职能——正面＝面对用户发呆、侧面＝横向走动（向左自动镜像）、背面＝转身不理人。
 * 悬浮窗做不到逐像素点击穿透，所以窗口就按角色轮廓开，只在角色身上挡一点。
 *
 * 由 [PetOverlayService] 持有与显隐；显隐、尺寸、位置、位置锁定都持久化在
 * `aharou_pet_prefs` 里。
 */
class PetOverlay(private val context: Context) {

    /** 桌宠尺寸档：高度按 dp，跟屏幕密度一起换算成像素。 */
    enum class Size(val key: String, val heightDp: Int) {
        SMALL("small", 150),
        MEDIUM("medium", 220),
        LARGE("large", 320);

        companion object {
            fun from(key: String?): Size = values().firstOrNull { it.key == key } ?: MEDIUM
        }
    }

    private enum class Motion { IDLE, WALK }

    private enum class Facing(val asset: String) {
        FRONT("front"),
        SIDE("side"),
        BACK("back")
    }

    companion object {
        internal const val PREFS = "aharou_pet_prefs"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        internal const val KEY_SIZE = "size"
        internal const val KEY_LOCKED = "locked"

        /** 服务没在跑时也能改设置：读写在同一个 prefs 上，下次 attach 生效。 */
        fun readSize(context: Context): Size =
            Size.from(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SIZE, null))

        fun writeSize(context: Context, size: Size) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SIZE, size.key).apply()
        }

        fun readLocked(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LOCKED, false)

        fun writeLocked(context: Context, locked: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_LOCKED, locked).apply()
        }

        private const val FRAME_MS = 33L          // ≈30fps
        private const val DRAG_SLOP_DP = 6
        private const val WALK_SPEED_DP = 85f
        private const val IDLE_MIN_MS = 3_000L
        private const val IDLE_MAX_MS = 9_000L
        private const val WALK_CHANCE = 0.6f
        private const val BUBBLE_MS = 3_500L
        private const val EDGE_PADDING_DP = 12
        private const val DOUBLE_TAP_MS = 280L
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val density = context.resources.displayMetrics.density

    private var root: LinearLayout? = null
    private var body: FrameLayout? = null
    private var sprite: ImageView? = null
    private var bubble: TextView? = null
    private var params: WindowManager.LayoutParams? = null

    private var bitmaps: MutableMap<String, Bitmap> = mutableMapOf()
    private var spriteKey: String? = null

    private var motion = Motion.IDLE
    private var facing = Facing.FRONT
    private var direction = 1
    private var phase = 0f
    private var targetX: Int? = null
    private var nextDecisionAt = 0L

    private var downAt = 0f to 0f
    private var dragging = false
    private var lastTapAt = 0L

    @Volatile
    var isShown: Boolean = false
        private set

    private val gestureDetector by lazy {
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                speakRandom()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                cycleFacing()
                return true
            }
        })
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!isShown) return
            step()
            mainHandler.postDelayed(this, FRAME_MS)
        }
    }

    private val hideBubble = Runnable {
        bubble?.visibility = View.GONE
    }

    fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    val currentSize: Size get() = Size.from(prefs.getString(KEY_SIZE, null))

    var isPositionLocked: Boolean
        get() = prefs.getBoolean(KEY_LOCKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LOCKED, value).apply()
        }

    // ── 显隐 ────────────────────────────────────────────────
    fun show() {
        if (!hasOverlayPermission()) {
            if (isShown) hide()
            return
        }
        mainHandler.post {
            runCatching {
                if (root == null) attach()
                if (isShown) return@runCatching
                isShown = true
                motion = Motion.IDLE
                facing = Facing.FRONT
                nextDecisionAt = System.currentTimeMillis() + randomIdle()
                mainHandler.removeCallbacks(tick)
                mainHandler.post(tick)
            }
        }
    }

    fun hide() {
        mainHandler.post {
            isShown = false
            mainHandler.removeCallbacks(tick)
            mainHandler.removeCallbacks(hideBubble)
            val v = root ?: return@post
            runCatching { windowManager.removeView(v) }
            root = null
            body = null
            sprite = null
            bubble = null
            params = null
            spriteKey = null
        }
    }

    fun setSize(size: Size) {
        prefs.edit().putString(KEY_SIZE, size.key).apply()
        mainHandler.post {
            releaseBitmaps()
            spriteKey = null
            if (root == null) return@post
            loadBitmaps(size)
            refreshSprite(force = true)
            clampIntoBounds()
            updateWindow()
        }
    }

    /** 屏幕旋转 / 尺寸变化后重新贴边，别让小人留在屏幕外。 */
    fun onConfigurationChanged(newConfig: Configuration) {
        mainHandler.post {
            if (root == null) return@post
            clampIntoBounds()
            updateWindow()
        }
    }

    fun release() {
        hide()
        releaseBitmaps()
    }

    // ── 视图 ────────────────────────────────────────────────
    private fun attach() {
        val vertical = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val bubbleView = TextView(context).apply {
            visibility = View.GONE
            maxLines = 3
            textSize = 12f
            setTextColor(Color.parseColor("#3C323C"))
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12 * density
                setColor(Color.argb(238, 255, 255, 255))
            }
            background = bg
            setPadding(
                (10 * density).toInt(), (7 * density).toInt(),
                (10 * density).toInt(), (7 * density).toInt(),
            )
        }
        val bodyFrame = FrameLayout(context)
        val image = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        bodyFrame.addView(image)
        vertical.addView(bubbleView)
        vertical.addView(bodyFrame)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val savedX = prefs.getInt(KEY_X, -1)
            val savedY = prefs.getInt(KEY_Y, -1)
            if (savedX >= 0 && savedY >= 0) {
                x = savedX
                y = savedY
            } else {
                x = screenWidth() - dpToPx(Size.MEDIUM.heightDp)
                y = screenHeight() - dpToPx(Size.MEDIUM.heightDp) - dpToPx(48)
            }
        }

        root = vertical
        body = bodyFrame
        sprite = image
        bubble = bubbleView
        params = layoutParams

        loadBitmaps(currentSize)

        image.setOnTouchListener { _, event -> handleTouch(event) }
        vertical.setOnTouchListener { _, event -> handleTouch(event) }

        windowManager.addView(vertical, layoutParams)
        clampIntoBounds()
        refreshSprite(force = true)
        updateWindow()
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        // 双击 / 单击交给手势识别；拖动自己处理。
        gestureDetector.onTouchEvent(event)
        if (isPositionLocked) return true
        val p = params ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = event.rawX to event.rawY
                dragging = false
                lastTapAt = System.currentTimeMillis()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downAt.first
                val dy = event.rawY - downAt.second
                if (!dragging && abs(dx) + abs(dy) < DRAG_SLOP_DP * density) return true
                dragging = true
                motion = Motion.IDLE
                targetX = null
                p.x += dx.roundToInt()
                p.y += dy.roundToInt()
                downAt = event.rawX to event.rawY
                clampIntoBounds()
                updateWindow()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    // 拖过就存位置；避开「手势识别把拖动当单击」的误报。
                    prefs.edit().putInt(KEY_X, p.x).putInt(KEY_Y, p.y).apply()
                    nextDecisionAt = System.currentTimeMillis() + randomIdle()
                }
                dragging = false
                return true
            }
        }
        return true
    }

    // ── 动画 ────────────────────────────────────────────────
    private fun step() {
        val p = params ?: return
        val now = System.currentTimeMillis()
        phase += FRAME_MS / 1000f

        if (motion == Motion.WALK) {
            val target = targetX
            if (target == null) {
                motion = Motion.IDLE
            } else {
                val dx = target - p.x
                val maxStep = WALK_SPEED_DP * density * FRAME_MS / 1000f
                if (abs(dx) <= maxStep) {
                    p.x = target
                    targetX = null
                    motion = Motion.IDLE
                    facing = Facing.FRONT
                    nextDecisionAt = now + randomIdle()
                } else {
                    direction = if (dx > 0) 1 else -1
                    p.x += (direction * maxStep).roundToInt()
                    clampIntoBounds()
                }
                updateWindow()
            }
        } else if (now >= nextDecisionAt) {
            if (Random.nextFloat() < WALK_CHANCE) {
                startWalk()
            } else {
                facing = when (Random.nextInt(3)) {
                    0 -> Facing.BACK
                    else -> Facing.FRONT
                }
                nextDecisionAt = now + randomIdle()
            }
        }

        refreshSprite(force = false)

        // 呼吸：整体轻微缩放；走动时改成上下起伏。
        val bodyView = body ?: return
        if (motion == Motion.WALK) {
            bodyView.scaleX = 1f
            bodyView.scaleY = 1f
            bodyView.translationY = -abs(3.5f * density * sin(phase * 8f))
        } else {
            bodyView.translationY = 0f
            val s = 1f + 0.015f * sin(phase * 2f)
            bodyView.scaleX = s
            bodyView.scaleY = s
        }
    }

    private fun startWalk() {
        val p = params ?: return
        val width = root?.width ?: 0
        if (width <= 0) {
            nextDecisionAt = System.currentTimeMillis() + randomIdle()
            return
        }
        val maxX = (screenWidth() - width).coerceAtLeast(0)
        if (maxX <= 0) {
            nextDecisionAt = System.currentTimeMillis() + randomIdle()
            return
        }
        val tx = Random.nextInt(0, maxX + 1)
        if (abs(tx - p.x) < width / 2) {
            nextDecisionAt = System.currentTimeMillis() + randomIdle()
            return
        }
        targetX = tx
        direction = if (tx > p.x) 1 else -1
        motion = Motion.WALK
    }

    private fun refreshSprite(force: Boolean) {
        val key = if (motion == Motion.WALK) {
            if (direction > 0) "side" else "side_left"
        } else {
            facing.asset
        }
        if (!force && key == spriteKey) return
        val source = bitmaps[if (key == "side_left") "side" else key] ?: return
        val view = sprite ?: return
        spriteKey = key
        view.setImageBitmap(source)
        view.scaleX = if (key == "side_left") -1f else 1f
    }

    private fun cycleFacing() {
        motion = Motion.IDLE
        targetX = null
        facing = when (facing) {
            Facing.FRONT -> Facing.SIDE
            Facing.SIDE -> Facing.BACK
            Facing.BACK -> Facing.FRONT
        }
        nextDecisionAt = System.currentTimeMillis() + randomIdle()
        refreshSprite(force = true)
    }

    // ── 说话 ────────────────────────────────────────────────
    private fun speakRandom() {
        val lines = context.resources.getStringArray(R.array.pet_lines)
        if (lines.isEmpty()) return
        val view = bubble ?: return
        view.text = lines[Random.nextInt(lines.size)]
        view.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideBubble)
        mainHandler.postDelayed(hideBubble, BUBBLE_MS)
    }

    // ── 工具 ────────────────────────────────────────────────
    private fun loadBitmaps(size: Size) {
        val target = dpToPx(size.heightDp)
        bitmaps = mutableMapOf(
            "front" to scaleAsset("front", target),
            "side" to scaleAsset("side", target),
            "back" to scaleAsset("back", target),
        ).filterValues { it != null }.mapValues { it.value!! }.toMutableMap()
    }

    private fun scaleAsset(name: String, targetHeight: Int): Bitmap? {
        return try {
            val decoded = context.assets.open("pet/$name.png").use { BitmapFactory.decodeStream(it) }
                ?: return null
            val scale = targetHeight.toFloat() / decoded.height
            val w = (decoded.width * scale).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(decoded, w, targetHeight, true)
            if (scaled !== decoded) decoded.recycle()
            scaled
        } catch (_: Throwable) {
            null
        }
    }

    private fun releaseBitmaps() {
        bitmaps.values.forEach { runCatching { it.recycle() } }
        bitmaps = mutableMapOf()
    }

    private fun updateWindow() {
        val v = root ?: return
        val p = params ?: return
        runCatching { windowManager.updateViewLayout(v, p) }
    }

    private fun clampIntoBounds() {
        val p = params ?: return
        val w = root?.width ?: 0
        val h = root?.height ?: 0
        val maxX = (screenWidth() - w).coerceAtLeast(0)
        val maxY = (screenHeight() - h).coerceAtLeast(0)
        p.x = p.x.coerceIn(0, maxX)
        p.y = p.y.coerceIn(0, maxY)
    }

    private fun randomIdle(): Long =
        Random.nextLong(IDLE_MIN_MS, IDLE_MAX_MS + 1)

    private fun screenWidth(): Int = context.resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = context.resources.displayMetrics.heightPixels

    private fun dpToPx(dp: Int): Int = (dp * density).roundToInt()
}
