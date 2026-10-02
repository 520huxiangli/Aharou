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
import android.view.VelocityTracker
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.aharou.R
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * 桌宠悬浮窗：用三视图（正面 / 侧面 / 打盹）拼出的小染，盖在其它 App 之上。
 *
 * 屏幕上的悬浮层只有她一个，所以这里同时承担三件事：
 *  - **形态**：待机呼吸、横向走动（向左自动镜像）、没人理就自己打盹、被拖动；
 *  - **气泡**：台词（自动收）/ 任务状态（常显）/ 通话字幕（常显），三种共用一个气泡；
 *  - **操作**：长按头部唤出径向菜单（[PetRadialMenu]），麦克风、说话、打盹、隐藏都在那儿。
 *
 * 一个必须知道的实现约束：悬浮窗就是那块矩形，**菜单展开时必须把窗口撑大**，圆环才画得下；
 * 撑大期间那块矩形会挡住下面的点击，所以收起时要立刻缩回角色轮廓。窗口位置一律由
 * 「角色在屏幕上的锚点」反推（见 [applyLayout]），任何尺寸/气泡/菜单变化都走它，她就不会跳。
 */
class PetOverlay(private val context: Context) {

    /** 桌宠尺寸档：高度按 dp，跟屏幕密度一起换算成像素。 */
    enum class Size(val key: String, val heightDp: Int) {
        SMALL("small", 100),
        MEDIUM("medium", 140),
        LARGE("large", 190);

        companion object {
            fun from(key: String?): Size = values().firstOrNull { it.key == key } ?: MEDIUM
        }
    }

    private enum class Motion { IDLE, WALK, FLING }

    /** 闲着的时候她会干的事。权重与冷却见 [decideIdle]。 */
    private enum class Idle { STAY, GLANCE, WALK, NAP }

    companion object {
        internal const val PREFS = "aharou_pet_prefs"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        internal const val KEY_SIZE = "size"
        internal const val KEY_LOCKED = "locked"
        internal const val KEY_ENABLED = "enabled"
        internal const val KEY_ALWAYS = "always"
        internal const val KEY_HIDDEN = "hidden"

        /** 摆件模式：整个悬浮窗不接触摸。 */
        private const val KEY_PASS_THROUGH = "pass_through"

        private const val FRAME_MS = 33L           // ≈30fps
        private const val IDLE_FRAME_MS = 200L     // 菜单展开后的降帧
        private const val DRAG_SLOP_DP = 6

        /**
         * 溜达速度（dp/秒）与步幅（dp/步）。步频由两者相除得出。
         *
         * 她只有一张侧视图、没有逐帧腿部动作，所以「像在走路」全靠叠加的起伏与微倾，
         * 只把她平移过去看起来就是滑行（见 [applyBodyMotion]）。
         */
        private const val WALK_SPEED_DP = 62f
        private const val WALK_STRIDE_DP = 26f
        private const val WALK_BOB_DP = 2.4f
        private const val WALK_TILT_DEG = 2f

        /** 拖动松手时离屏边这么近就吸过去（dp）。 */
        private const val EDGE_SNAP_DP = 28

        /** 探头素材的缩放基准：素材管线里「1 个身高 = 512 像素」，按它缩小才跟其它姿势的头一样大。 */
        private const val PEEK_CANON = 512f

        /** 收/出的滑动时长（秒），跟主流悬浮窗贴边动画一个量级。 */
        private const val TUCK_SECONDS = 0.30f

        /** 被叫出来后至少隔这么久才允许再收起，免得你在戳她她还往边上缩。 */
        private const val TUCK_COOLDOWN_MS = 20_000L

        /** 贴在边上待机时，有多大概率选择「收进去」。 */
        private const val TUCK_CHANCE = 0.8f

        /** 贴边站住后多久做下一次决定：够短，让她很快收进去。 */
        private const val EDGE_SETTLE_MS = 2_500L

        /** 甩出去：阈值（px/秒）与衰减系数（越大停得越快）、撞屏边的反弹系数。 */
        private const val FLING_MIN_VX = 800f
        private const val FLING_MAX_VX = 3000f
        private const val FLING_DECAY = 3.2f
        private const val FLING_BOUNCE = 0.45f

        /** 连点判定：这段时间内的单击算同一串。 */
        private const val TAP_COMBO_MS = 2_000L

        private const val IDLE_MIN_MS = 3_000L
        private const val IDLE_MAX_MS = 9_000L
        private const val BUBBLE_MS = 3_500L
        private const val EDGE_PADDING_DP = 10
        private const val BUBBLE_MIN_WIDTH_DP = 132

        /** 没人搭理她这么久就自己打盹（毫秒）；深夜缩短，她更早睡。 */
        private const val SLEEP_AFTER_MS = 90_000L

        /** 深夜（真正冷清的时段）打盹阈值。 */
        private const val SLEEP_AFTER_MS_NIGHT = 20_000L

        /** 各闲事的冷却，避免刚走完又走、刚看完又看。 */
        private const val GLANCE_COOLDOWN_MS = 8_000L
        private const val WALK_COOLDOWN_MS = 3_000L
        private const val NAP_COOLDOWN_MS = 120_000L

        /** 打盹时降帧：呼吸本来就慢，没必要跑 30fps 费电。 */
        private const val SLEEP_FRAME_MS = 110L

        /** 头部热区（长按唤菜单）占角色高度的比例。 */
        private const val HEAD_TOP_RATIO = 0.02f
        private const val HEAD_HEIGHT_RATIO = 0.36f
        private const val MENU_HEAD_CENTER_RATIO = 0.20f

        /** 隔这么多天没见，重逢时她会说句话。 */
        private const val MISS_YOU_DAYS = 3

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

        fun readPassThrough(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_PASS_THROUGH, false)

        fun writePassThrough(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_PASS_THROUGH, on).apply()
        }
    }

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val density = context.resources.displayMetrics.density

    private var root: FrameLayout? = null
    private var petColumn: LinearLayout? = null
    private var body: FrameLayout? = null
    private var sprite: ImageView? = null
    private var bubble: TextView? = null
    var menu: PetRadialMenu? = null
        private set
    private var params: WindowManager.LayoutParams? = null

    private var bitmaps: MutableMap<String, Bitmap> = mutableMapOf()
    private var spriteKey: String? = null
    private var spriteW = 0
    private var spriteH = 0

    /** 角色左上角在屏幕上的位置——窗口位置由它反推，拖拽改的也是它。 */
    private var anchorX = 0
    private var anchorY = 0

    /** 气泡高度缓存：每帧去 measure 会拖慢溜达动画，只在内容变化时重新量。 */
    private var bubbleHeightPx = 0

    private var motion = Motion.IDLE
    private var direction = 1
    private var phase = 0f
    private var lastFrameAt = 0L
    private var targetX: Int? = null
    private var nextDecisionAt = 0L

    /** 走路两帧：0=并腿、1=迈步，一步换一帧。 */
    private var walkFrame = 0

    private var idle = Idle.STAY
    private var idleUntil = 0L
    private val idleCooldowns = HashMap<Idle, Long>()

    /** 甩出去的水平速度（px/秒），只用在 [Motion.FLING]。 */
    private var flingVx = 0f

    /** 连点计数与上一次点击的时间。 */
    private var tapCount = 0
    private var lastTapAt = 0L

    /** 单击挥手的截止时间：说话时换成打招呼的姿势，气泡收起就转回正面待机。 */
    private var waveUntil = 0L

    private val velocityTracker = VelocityTracker.obtain()

    /** 打盹状态与「上次被搭理」的时点，长时间没人理就进去。 */
    private var sleeping = false
    private var sleepingBubble = false
    private var lastInteractionAt = 0L

    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var inHeadZone = false
    /** 正在播一次性动作动画（摸头 / 落地 / 被戳烦），期间不受呼吸起伏干扰。 */
    private var oneShot = false

    private var bubblePersistent = false
    private var inCall = false

    private val passThrough: Boolean get() = prefs.getBoolean(KEY_PASS_THROUGH, false)

    /** 扔出去的小球：独立小窗（主窗只圈得住她那么大，球会出界）。 */
    private val toy = PetToy(context, windowManager)

    /** 正在跑去捡球（走到目标点就捡起来）。 */
    private var chasing = false

    /** 贴边收起：0 = 完全在外，1 = 只留一条贴在屏幕边。 */
    private var tuckProgress = 0f
    private var tuckTarget = 0f

    /** 这个时间点之前不许再收起。 */
    private var tuckBlockedUntil = 0L

    /** 她对你的态度：摸头加分、被戳掉分，落盘跨会话保留。 */
    private var mood: PetMood = PetMoodStore.read(context)

    /** 径向菜单回调：由服务接管（通话开关、隐藏等要动服务状态）。 */
    var onMenuAction: ((String) -> Unit)? = null

    /** 要台词的回调：服务接上就带记忆去生成，没接就用写死的台词（见 [speakRandom]）。 */
    var onSpeakRequest: (() -> Unit)? = null

    @Volatile
    var isShown: Boolean = false
        private set

    private val gestureDetector by lazy {
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val now = System.currentTimeMillis()
                tapCount = if (now - lastTapAt <= TAP_COMBO_MS) tapCount + 1 else 1
                lastTapAt = now
                if (tapCount >= 3) {
                    tapCount = 0
                    annoyed()
                } else {
                    speakRandom()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!inHeadZone) return true
                patHead()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // 头部唤菜单，身上别的地方＝想抱她
                if (inHeadZone) openMenu() else hug()
            }
        })
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!isShown) return
            step()
            val interval = when {
                toy.isActive -> FRAME_MS
                menu?.isOpen == true -> IDLE_FRAME_MS
                sleeping -> SLEEP_FRAME_MS
                else -> FRAME_MS
            }
            mainHandler.postDelayed(this, interval)
        }
    }

    private val hideBubble = Runnable {
        if (!bubblePersistent) applyBubble("", persistent = false)
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
                targetX = null
                sleeping = false
                lastFrameAt = System.currentTimeMillis()
                markInteraction()
                greet()
                applyLayout()
                mainHandler.removeCallbacks(tick)
                mainHandler.post(tick)
            }
        }
    }

    /**
     * 截屏前后把自己（和球）收一下：她是悬浮窗，不收掉就会被截进画面，
     * 发到会话里 AI 会跑去解读那只桌宠。窗口留着，只是不画，收回来时不用重建。
     */
    fun setCaptureHidden(hidden: Boolean) {
        mainHandler.post {
            root?.visibility = if (hidden) View.GONE else View.VISIBLE
            toy.setWindowHidden(hidden)
        }
    }

    fun hide() {
        mainHandler.post {
            isShown = false
            mainHandler.removeCallbacks(tick)
            mainHandler.removeCallbacks(hideBubble)
            menu?.close()
            val v = root ?: return@post
            runCatching { windowManager.removeView(v) }
            root = null
            body = null
            petColumn = null
            sprite = null
            bubble = null
            menu = null
            params = null
            spriteKey = null
            sleeping = false
            sleepingBubble = false
            bubbleHeightPx = 0
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
            anchorX = anchorX.coerceIn(0, (screenWidth() - spriteW).coerceAtLeast(0))
            anchorY = anchorY.coerceIn(0, (screenHeight() - spriteH).coerceAtLeast(0))
            applyLayout()
        }
    }

    /** 屏幕旋转 / 尺寸变化后重新贴边，别让小人留在屏幕外。 */
    fun onConfigurationChanged(newConfig: Configuration) {
        mainHandler.post {
            if (root == null) return@post
            menu?.close()
            anchorX = anchorX.coerceIn(0, (screenWidth() - spriteW).coerceAtLeast(0))
            anchorY = anchorY.coerceIn(0, (screenHeight() - spriteH).coerceAtLeast(0))
            applyLayout()
        }
    }

    fun release() {
        hide()
        releaseBitmaps()
        toy.dismiss()
        runCatching { velocityTracker.recycle() }
    }

    // ── 气泡 ────────────────────────────────────────────────
    /**
     * 台词：优先问服务要一句（会带记忆去生成），没接那条路就用写死的三档台词兜底。
     */
    fun speakRandom() {
        val request = onSpeakRequest
        if (request != null) request.invoke() else speakStaticLine()
    }

    /** 写死的三档台词（按好感度分冷/常/亲），也是模型那条路的降级路径。 */
    fun speakStaticLine() {
        val res = when {
            // 闹别扭或被冷落到冷淡档：只说冷话
            mood.isSulking() || mood.level == PetMood.Level.COLD -> R.array.pet_lines_cold
            mood.level == PetMood.Level.NORMAL -> R.array.pet_lines
            else -> R.array.pet_lines_warm
        }
        val all = context.resources.getStringArray(res)
            .ifEmpty { context.resources.getStringArray(R.array.pet_lines) }
        if (all.isEmpty()) return
        val said = PetMoodStore.readTodaySaid(context)
        // 排除今日已说过的；若全部说完则清空重来（至少保证能说出一句）
        val candidates = all.filter { it !in said }.ifEmpty { all.toList() }
        val chosen = candidates[Random.nextInt(candidates.size)]
        PetMoodStore.markSaid(context, chosen)
        waveUntil = System.currentTimeMillis() + BUBBLE_MS
        applyBubble(chosen, persistent = false)
    }

    /** 直接冒一句（服务用来播报任务结果、提醒、以及生成出来的台词）。[wave] 为真时顺手挥个手。 */
    fun say(text: String, holdMs: Long = BUBBLE_MS, wave: Boolean = false) {
        // 收起状态下气泡会被裁掉，先把她叫出来再说
        if (text.isNotBlank()) untuck()
        if (wave) waveUntil = System.currentTimeMillis() + holdMs
        applyBubble(text, persistent = false, holdMs = holdMs)
    }

    /** 现身时结算离线衰减，并在久别（隔了三个自然日以上）时说一句重逢台词。 */
    private fun greet() {
        val (settled, missed) = mood.appeared(System.currentTimeMillis())
        mood = settled
        PetMoodStore.write(context, mood)
        if (missed >= MISS_YOU_DAYS) {
            applyBubble(context.getString(R.string.pet_missed_you), persistent = false)
        }
    }

    /** 常显气泡：任务状态 / 通话字幕，传空串等于清掉。 */
    fun showStatus(text: String) {
        mainHandler.post {
            if (text.isBlank()) {
                // 「Zzz…」是她自己的气泡，状态流空着的时候别把它擦掉
                if (sleepingBubble) return@post
            } else {
                // 有进度说明有人在替她干活了，得醒着、也得从屏幕边出来（不然字幕看不见）
                sleepingBubble = false
                untuck()
                markInteraction()
            }
            applyBubble(text, persistent = text.isNotBlank())
        }
    }

    private fun applyBubble(text: String, persistent: Boolean, holdMs: Long = BUBBLE_MS) {
        mainHandler.post {
            val view = bubble ?: return@post
            bubblePersistent = persistent
            mainHandler.removeCallbacks(hideBubble)
            if (text.isBlank()) {
                if (view.visibility == View.VISIBLE) {
                    view.visibility = View.GONE
                    bubbleHeightPx = 0
                    applyLayout()
                }
                return@post
            }
            view.text = text
            view.visibility = View.VISIBLE
            bubbleHeightPx = measureBubbleHeight()
            applyLayout()
            if (!persistent) mainHandler.postDelayed(hideBubble, holdMs)
        }
    }

    /** 通话状态：只同步菜单里麦克风的开启态，屏幕上不再额外画光环。 */
    fun setCallState(inCall: Boolean) {
        mainHandler.post {
            if (this.inCall == inCall) return@post
            this.inCall = inCall
            menu?.setItemActive(PetRadialMenu.ID_CALL, inCall)
            // 通话等于有人在跟她说话，得醒着
            if (inCall) markInteraction()
        }
    }

    // ── 视图 ────────────────────────────────────────────────
    private fun attach() {
        val frame = FrameLayout(context)

        val bubbleView = TextView(context).apply {
            visibility = View.GONE
            maxLines = 4
            textSize = 11.5f
            setTextColor(Color.parseColor("#3C323C"))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12 * density
                setColor(Color.argb(238, 255, 255, 255))
            }
            setPadding(
                (9 * density).toInt(), (6 * density).toInt(),
                (9 * density).toInt(), (6 * density).toInt(),
            )
        }

        val bodyFrame = FrameLayout(context)
        val image = ImageView(context).apply {
            adjustViewBounds = true
            // CENTER 而不是 FIT_CENTER：贴边收起时窗口会缩到只剩探头那一块，
            // FIT_CENTER 会把整张立绘等比例缩小塞进去（看起来就是「变小了」），
            // CENTER 按原始尺寸画、超出窗口的部分自然被裁掉——那才是半隐藏。
            scaleType = ImageView.ScaleType.CENTER
        }
        bodyFrame.addView(image)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(bubbleView)
            addView(bodyFrame)
        }

        val menuView = PetRadialMenu(context).apply {
            visibility = View.GONE
            onAction = { id -> handleMenuAction(id) }
        }

        frame.addView(column)
        frame.addView(menuView)

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
            baseFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        root = frame
        body = bodyFrame
        petColumn = column
        sprite = image
        bubble = bubbleView
        menu = menuView
        params = layoutParams

        loadBitmaps(currentSize)

        val savedX = prefs.getInt(KEY_X, -1)
        val savedY = prefs.getInt(KEY_Y, -1)
        if (savedX >= 0 && savedY >= 0) {
            anchorX = savedX
            anchorY = savedY
        } else {
            anchorX = screenWidth() - spriteW - dpToPx(EDGE_PADDING_DP)
            anchorY = screenHeight() - spriteH - dpToPx(56)
        }
        anchorX = anchorX.coerceIn(0, (screenWidth() - spriteW).coerceAtLeast(0))
        anchorY = anchorY.coerceIn(0, (screenHeight() - spriteH).coerceAtLeast(0))

        image.setOnTouchListener { _, event -> handleTouch(event) }
        column.setOnTouchListener { _, event -> handleTouch(event) }

        windowManager.addView(frame, layoutParams)
        toy.onRest = { x -> chaseToy(x) }
        refreshSprite(force = true)
        applyLayout()
    }

    /** 基础窗口属性：不抢焦点、不接管全屏触摸。 */
    private fun baseFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            (if (passThrough) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)

    /** 摆件模式切换后重新应用窗口属性（触摸穿透只能这样改）。 */
    fun setPassThrough(on: Boolean) {
        mainHandler.post {
            if (root == null) return@post
            val p = params ?: return@post
            p.flags = baseFlags()
            toy.setTouchable(!on)
            runCatching { windowManager.updateViewLayout(root, p) }
        }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        if (menu?.isOpen == true) return false // 菜单开着时由菜单自己接
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                dragging = false
                inHeadZone = isInHeadZone(event.x, event.y)
                velocityTracker.clear()
                velocityTracker.addMovement(event)
                markInteraction()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPositionLocked) return true
                velocityTracker.addMovement(event)
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && abs(dx) + abs(dy) < DRAG_SLOP_DP * density) return true
                dragging = true
                motion = Motion.IDLE
                targetX = null
                anchorX += dx.roundToInt()
                anchorY += dy.roundToInt()
                downRawX = event.rawX
                downRawY = event.rawY
                clampAnchor()
                applyLayout()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    snapToEdge()
                    prefs.edit().putInt(KEY_X, anchorX).putInt(KEY_Y, anchorY).apply()
                    nextDecisionAt = System.currentTimeMillis() + randomIdle()
                    // 松手时还在快就不算「放下」，算「甩出去」
                    val vx = if (event.actionMasked == MotionEvent.ACTION_UP) {
                        velocityTracker.computeCurrentVelocity(1000)
                        velocityTracker.xVelocity
                    } else {
                        0f
                    }
                    if (abs(vx) >= FLING_MIN_VX) startFling(vx) else playLanding()
                }
                dragging = false
                inHeadZone = false
                return true
            }
        }
        return true
    }

    /**
     * 头部热区：角色顶部的一条横带（长按出菜单、双击摸头都只认这里）。
     *
     * 只做纵向限定、不查像素透明度：立绘是固定三视图，横带范围内基本都是头，
     * 逐像素查 alpha 得不偿失。
     */
    private fun isInHeadZone(x: Float, y: Float): Boolean {
        val view = sprite ?: return false
        val top = view.top.toFloat()
        val headTop = top + spriteH * HEAD_TOP_RATIO
        val headBottom = top + spriteH * (HEAD_TOP_RATIO + HEAD_HEIGHT_RATIO)
        return y in headTop..headBottom
    }

    // ── 径向菜单 ────────────────────────────────────────────
    private fun openMenu() {
        val view = menu ?: return
        // 菜单要完整画在她身边，收起状态先直接拉出来（不慢慢滑，免得跟菜单抢位置）
        tuckTarget = 0f
        tuckProgress = 0f
        tuckBlockedUntil = System.currentTimeMillis() + TUCK_COOLDOWN_MS
        motion = Motion.IDLE
        targetX = null
        val pad = menuPad()
        val petW = maxOf(spriteW, dpToPx(BUBBLE_MIN_WIDTH_DP))
        val spriteOffset = (petW - spriteW) / 2f
        val bubbleH = bubbleHeightPx.toFloat()
        val cx = pad + spriteOffset + spriteW / 2f
        val cy = pad + bubbleH + spriteH * MENU_HEAD_CENTER_RATIO
        view.open(
            listOf(
                PetRadialMenu.Item(
                    PetRadialMenu.ID_CALL, R.drawable.ic_pet_mic,
                    context.getString(R.string.pet_menu_call), inCall,
                ),
                PetRadialMenu.Item(
                    PetRadialMenu.ID_SHOT, R.drawable.ic_pet_shot,
                    context.getString(R.string.pet_menu_shot), false,
                ),
                PetRadialMenu.Item(
                    PetRadialMenu.ID_SAY, R.drawable.ic_pet_say,
                    context.getString(R.string.pet_menu_say), false,
                ),
                PetRadialMenu.Item(
                    PetRadialMenu.ID_TOY, R.drawable.ic_pet_ball,
                    context.getString(R.string.pet_menu_toy), false,
                ),
                PetRadialMenu.Item(
                    PetRadialMenu.ID_HIDE, R.drawable.ic_pet_hide,
                    context.getString(R.string.pet_menu_hide), false,
                ),
                PetRadialMenu.Item(
                    PetRadialMenu.ID_PASS, R.drawable.ic_pet_pass,
                    context.getString(R.string.pet_menu_pass), passThrough,
                ),
                PetRadialMenu.Item(
                    PetRadialMenu.ID_SLEEP,
                    if (sleeping) R.drawable.ic_pet_wake else R.drawable.ic_pet_sleep,
                    context.getString(
                        if (sleeping) R.string.pet_menu_wake else R.string.pet_menu_sleep
                    ),
                    sleeping,
                ),
            ),
            cx, cy, menuRadius(),
        )
        view.onDismissed = { applyLayout() }
        applyLayout()
    }

    private fun handleMenuAction(id: String) {
        if (id == PetRadialMenu.ID_SAY) {
            menu?.close()
            speakRandom()
            return
        }
        if (id == PetRadialMenu.ID_SLEEP) {
            menu?.close()
            toggleSleep()
            return
        }
        if (id == PetRadialMenu.ID_TOY) {
            menu?.close()
            tossToy()
            return
        }
        if (id == PetRadialMenu.ID_PASS) {
            menu?.close()
            onMenuAction?.invoke(id)
            return
        }
        // 通话开关与隐藏要动服务状态：先收菜单，再交给外面。
        menu?.close()
        onMenuAction?.invoke(id)
    }

    private fun menuRadius(): Float = maxOf(spriteW * 0.85f, 54 * density)

    private fun menuPad(): Int =
        (menuRadius() + 21 * density + 20 * density + 4 * density).roundToInt()

    // ── 布局：窗口位置一律由角色锚点反推 ──────────────────────
    private fun applyLayout() {
        val v = root ?: return
        val p = params ?: return
        if (spriteW <= 0 || spriteH <= 0) return

        val menuOpen = menu?.isOpen == true
        val pad = if (menuOpen) menuPad() else 0
        val bubbleH = bubbleHeightPx
        // 收起＝换成「贴边探头」那张素材（侧身、只露头肩）。素材没加载上就干脆不收，
        // 别用别的图片硬裁——那样出来的是「她身体被竖着切一刀」。
        val peek = bitmaps["peek"]
        val t = if (peek == null || pad > 0 || bubbleH > 0) 0f else eased(tuckProgress)

        val width = lerp(
            maxOf(spriteW, dpToPx(BUBBLE_MIN_WIDTH_DP)) + pad * 2f,
            peek?.width?.toFloat() ?: 0f,
            t,
        ).roundToInt()
        val height = lerp(spriteH + bubbleH + pad * 2f, peek?.height?.toFloat() ?: 0f, t).roundToInt()
        // 头肩那张图左边就是切面：贴左边缘正好对齐，贴右边用镜像图
        val x = lerp(
            anchorX - pad.toFloat(),
            if (anchorX <= 0) 0f else screenWidth() - (peek?.width ?: 0).toFloat(),
            t,
        ).roundToInt()
        // 窗口顶端对齐她的发顶：换成探头姿势后她的头还在原地，不会整只跳一下
        val y = anchorY - ((pad + bubbleH) * (1f - t)).roundToInt()

        val resized = p.width != width || p.height != height
        if (resized) {
            p.width = width
            p.height = height
            (petColumn?.layoutParams as? FrameLayout.LayoutParams)?.let {
                it.leftMargin = pad
                it.topMargin = pad
            }
            petColumn?.requestLayout()
        }
        if (!resized && p.x == x && p.y == y) return
        p.x = x
        p.y = y
        runCatching { windowManager.updateViewLayout(v, p) }
    }

    /** 气泡高度只在内容变化时量一次（调用方负责缓存到 [bubbleHeightPx]）。 */
    private fun measureBubbleHeight(): Int {
        val view = bubble ?: return 0
        if (view.visibility != View.VISIBLE) return 0
        view.measure(
            View.MeasureSpec.makeMeasureSpec(
                dpToPx(BUBBLE_MIN_WIDTH_DP) * 2,
                View.MeasureSpec.AT_MOST,
            ),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return view.measuredHeight
    }

    private fun clampAnchor() {
        anchorX = anchorX.coerceIn(0, (screenWidth() - spriteW).coerceAtLeast(0))
        anchorY = anchorY.coerceIn(0, (screenHeight() - spriteH).coerceAtLeast(0))
    }

    // ── 动画 ────────────────────────────────────────────────
    private fun step() {
        if (spriteW <= 0) return
        val now = System.currentTimeMillis()
        // 相位按真实间隔推进：菜单展开与打盹时会降帧，动画速度不该跟着变慢。
        val dt = if (lastFrameAt == 0L) FRAME_MS / 1000f else (now - lastFrameAt) / 1000f
        lastFrameAt = now
        phase += dt.coerceIn(0f, 0.25f)

        when {
            // 打盹时不动地方，被碰一下就醒（见 markInteraction）
            sleeping -> Unit
            tucked() -> Unit
            motion == Motion.WALK -> {
                val target = targetX
                if (target == null) {
                    motion = Motion.IDLE
                } else {
                    val dx = target - anchorX
                    val maxStep = WALK_SPEED_DP * density * dt
                    if (abs(dx) <= maxStep) {
                        anchorX = target
                        targetX = null
                        motion = Motion.IDLE
                        // 贴边站着的时候多待一会儿，别刚到就往回走
                        // 贴边站住后很快再做一次决定（多半就是收进去），别磨哪半天
                        nextDecisionAt = now + if (atEdge()) EDGE_SETTLE_MS else randomIdle()
                        if (chasing) {
                            chasing = false
                            toy.pick()
                            applyBubble(context.getString(R.string.pet_toy_picked), false)
                        }
                    } else {
                        direction = if (dx > 0) 1 else -1
                        anchorX += (direction * maxStep).roundToInt()
                        clampAnchor()
                    }
                    // 一步换一帧腿，配上起伏才像在迈步
                    walkFrame = walkStepPhase().toInt() and 1
                    applyLayout()
                }
            }
            motion == Motion.FLING -> {
                anchorX += (flingVx * dt).roundToInt()
                flingVx *= exp(-FLING_DECAY * dt)
                clampAnchor()
                val maxX = (screenWidth() - spriteW).coerceAtLeast(0)
                val hitEdge = anchorX <= 0 || anchorX >= maxX
                if (hitEdge && abs(flingVx) > FLING_MIN_VX) {
                    // 撞屏边：软弹回去，弹到余速不够就停
                    flingVx = -flingVx * FLING_BOUNCE
                    direction = if (flingVx > 0) 1 else -1
                    playLanding()
                } else if (hitEdge || abs(flingVx) < FLING_MIN_VX / 4f) {
                    flingVx = 0f
                    motion = Motion.IDLE
                    nextDecisionAt = now + randomIdle()
                    if (!hitEdge) playLanding()
                }
                applyLayout()
            }
            idle == Idle.GLANCE && now >= idleUntil -> {
                idle = Idle.STAY
                nextDecisionAt = now + randomIdle()
            }
            // 贴边站着又没人理的时候，「歇着」就是缩进去：先缩再歇。
            // 不能直接 enterSleep：她睡着后就不再考虑收起了，而夜里 20 秒就睡着，
            // 结果就是永远看不到收起。
            now - lastInteractionAt >= sleepAfter() -> {
                if (canTuck() && atEdge()) {
                    tuckTarget = 1f
                } else {
                    enterSleep()
                }
            }
            now >= nextDecisionAt && menu?.isOpen != true -> decideIdle(now)
        }

        // 贴边收起／出来的滑动：滑动期间窗口位置每帧重算
        if (tuckProgress != tuckTarget) {
            val step = dt / TUCK_SECONDS
            tuckProgress = if (tuckTarget > tuckProgress) {
                (tuckProgress + step).coerceAtMost(tuckTarget)
            } else {
                (tuckProgress - step).coerceAtLeast(tuckTarget)
            }
            applyLayout()
        }

        refreshSprite(force = false)
        toy.update(dt)
        applyBodyMotion()
    }

    /**
     * 身体动画：待机呼吸 / 走路起伏 / 打盹慢呼吸，三态互斥。
     *
     * 只作用在身体容器上，不碰外层 column —— 不然头顶气泡会跟着一起歪、一起颠。
     */
    private fun applyBodyMotion() {
        val view = body ?: return
        if (oneShot || dragging) return

        if (sleeping) {
            val breath = sin(phase * 1.1f)
            view.rotation = 0f
            view.translationY = 0f
            view.scaleX = 1f + 0.02f * breath
            view.scaleY = 1f + 0.03f * breath
            return
        }

        if (motion == Motion.FLING) {
            // 滑行不是迈步：只留一点朝前的倾，不颠不缩
            view.rotation = WALK_TILT_DEG * 1.5f * direction
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }

        if (motion == Motion.WALK) {
            // 一步一个起伏：|sin| 的周期是完整正弦的一半，频率刚好等于步频
            val bob = abs(sin((walkStepPhase() * PI).toFloat()))
            view.translationY = -dpToPxF(WALK_BOB_DP) * bob
            view.scaleX = 1f + 0.012f * bob
            view.scaleY = 1f - 0.02f * bob
            view.rotation = WALK_TILT_DEG * direction
            return
        }

        view.rotation = 0f
        view.translationY = 0f
        val s = 1f + 0.012f * sin(phase * 2f)
        view.scaleX = s
        view.scaleY = s
    }

    private fun startWalk() {
        val width = spriteW
        val maxX = (screenWidth() - width).coerceAtLeast(0)
        if (maxX <= 0) {
            nextDecisionAt = System.currentTimeMillis() + randomIdle()
            return
        }
        idle = Idle.STAY
        idleCooldowns[Idle.WALK] = System.currentTimeMillis() + WALK_COOLDOWN_MS
        // 她只往屏幕两侧走：贴边站着挡到的东西最少，中间不待。
        // 已经在一边时抽到同一边就原地不动（见下面的距离判断）。
        val tx = if (Random.nextBoolean()) 0 else maxX
        if (abs(tx - anchorX) < width / 2) {
            nextDecisionAt = System.currentTimeMillis() + randomIdle()
            return
        }
        targetX = tx
        direction = if (tx > anchorX) 1 else -1
        motion = Motion.WALK
    }

    /** 是不是贴在屏幕左/右边缘站着。 */
    private fun atEdge(): Boolean =
        anchorX <= 0 || anchorX >= (screenWidth() - spriteW).coerceAtLeast(0)

    /** 已经缩到只留一条了（含滑动过半）。 */
    private fun tucked(): Boolean = tuckProgress > 0.5f

    /** 现在能不能收进去：拖动、菜单、追球、一次性动作、常显气泡都不行。 */
    private fun canTuck(): Boolean =
        !dragging && menu?.isOpen != true && !chasing && !oneShot && !bubblePersistent &&
            System.currentTimeMillis() >= tuckBlockedUntil

    /** 把她从屏幕边叫出来（碰一下、开菜单、冒字幕都会用到）。 */
    private fun untuck() {
        if (tuckTarget <= 0f && tuckProgress <= 0f) return
        tuckTarget = 0f
        tuckBlockedUntil = System.currentTimeMillis() + TUCK_COOLDOWN_MS
    }

    /** 滑动用的缓动（首尾慢、中间快）。 */
    private fun eased(t: Float): Float = t * t * (3f - 2f * t)

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    /** 被甩出去：带惯性滑一段，撞到屏幕边会软软弹回来。 */
    private fun startFling(vx: Float) {
        flingVx = vx.coerceIn(-FLING_MAX_VX, FLING_MAX_VX)
        direction = if (flingVx > 0) 1 else -1
        targetX = null
        motion = Motion.FLING
        refreshSprite(force = true)
    }

    /** 扔个球出去：她手边起飞，落地后她会自己跑去捡。 */
    private fun tossToy() {
        untuck()
        toy.toss(
            fromCenterX = anchorX + spriteW / 2,
            fromCenterY = anchorY + spriteH / 3,
            towardRight = Random.nextBoolean(),
        )
        chasing = false
        nextDecisionAt = System.currentTimeMillis() + 2_000
    }

    /** 球停稳了：动身去护。 */
    private fun chaseToy(x: Int) {
        val maxX = (screenWidth() - spriteW).coerceAtLeast(0)
        val target = (x - spriteW / 2).coerceIn(0, maxX)
        targetX = target
        direction = if (target > anchorX) 1 else -1
        motion = Motion.WALK
        idle = Idle.STAY
        chasing = true
    }

    private fun refreshSprite(force: Boolean) {
        val sideways = motion == Motion.WALK || motion == Motion.FLING
        val key = when {
            sleeping -> "sleep"
            dragging -> "drag"
            // 贴边收起（含滑动过程）：换侧身、脸朝屏幕里，看上去像从边上探头
            tuckTarget > 0.5f || tucked() -> if (anchorX <= 0) "peek" else "peek_left"
            sideways -> {
                val base = if (motion == Motion.WALK && walkFrame == 1) "side_walk" else "side"
                if (direction > 0) base else base + "_left"
            }
            idle == Idle.GLANCE -> if (direction > 0) "side" else "side_left"
            System.currentTimeMillis() < waveUntil -> "wave"
            else -> "front"
        }
        if (!force && key == spriteKey) return
        val mirrored = key.endsWith("_left")
        val name = if (mirrored) key.removeSuffix("_left") else key
        // 姿势图还没就位时降到已有的图，别让她整只消失
        val source = bitmaps[name] ?: bitmaps[if (sideways) "side" else "front"] ?: return
        val view = sprite ?: return
        spriteKey = key
        view.setImageBitmap(source)
        view.scaleX = if (mirrored) -1f else 1f
    }

    /** 松手时她要是就在屏幕边附近，直接吸到边上（拖到边就贴住）。 */
    private fun snapToEdge() {
        val maxX = (screenWidth() - spriteW).coerceAtLeast(0)
        val snap = dpToPx(EDGE_SNAP_DP)
        anchorX = when {
            anchorX <= snap -> 0
            anchorX >= maxX - snap -> maxX
            else -> anchorX
        }
    }

    /** 已经走了多少步（浮点）：步频＝速度÷步幅。 */
    private fun walkStepPhase(): Float = phase * WALK_SPEED_DP / WALK_STRIDE_DP

    /**
     * 闲着时挑一件事做：待机 / 往旁边矒两眼 / 溜达 / 打盹。
     *
     * 权重是「基础值 × 时段」：深夜打盹权重大，白天几乎不主动睡（反正等久了也会自己睡着，
     * 见 [sleepAfter]）。每件事都有冷却，不会刚走完又走。
     */
    private fun decideIdle(now: Long) {
        // 已经贴在屏幕边上了：多数时候干脆收进去（换成侧身探头那张素材）
        if (canTuck() && atEdge() && Random.nextFloat() < TUCK_CHANCE) {
            tuckTarget = 1f
            idle = Idle.STAY
            nextDecisionAt = now + randomIdle()
            return
        }
        val options = mutableListOf(Idle.STAY to 30f)
        if (ready(Idle.GLANCE, now)) options += Idle.GLANCE to 26f
        if (ready(Idle.WALK, now)) options += Idle.WALK to 28f
        if (ready(Idle.NAP, now)) options += Idle.NAP to if (isNight()) 40f else 5f

        nextDecisionAt = now + randomIdle()
        when (pick(options)) {
            Idle.WALK -> startWalk()
            Idle.GLANCE -> startGlance(now)
            Idle.NAP -> enterSleep()
            Idle.STAY -> Unit
        }
    }

    private fun ready(kind: Idle, now: Long): Boolean = now >= (idleCooldowns[kind] ?: 0L)

    private fun pick(options: List<Pair<Idle, Float>>): Idle {
        val total = options.sumOf { it.second.toDouble() }.toFloat()
        var roll = Random.nextFloat() * total
        for ((kind, weight) in options) {
            roll -= weight
            if (roll <= 0f) return kind
        }
        return options.last().first
    }

    /** 往旁边矒两眼：拿侧视图顶替，一两秒后转回来。 */
    private fun startGlance(now: Long) {
        idle = Idle.GLANCE
        direction = if (Random.nextBoolean()) 1 else -1
        idleUntil = now + Random.nextLong(1_200L, 2_600L)
        idleCooldowns[Idle.GLANCE] = idleUntil + GLANCE_COOLDOWN_MS
        nextDecisionAt = idleUntil
    }

    /** 深夜她更早自己睡着。 */
    private fun sleepAfter(): Long = if (isNight()) SLEEP_AFTER_MS_NIGHT else SLEEP_AFTER_MS

    private fun isNight(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 23 || hour < 6
    }

    /** 手动打盹 / 叫醒（菜单里那项）。 */
    private fun toggleSleep() {
        if (sleeping) wakeUp() else enterSleep()
        refreshSprite(force = true)
    }

    private fun enterSleep() {
        motion = Motion.IDLE
        targetX = null
        // 通话中得醒着报字幕，不睡
        if (inCall) return
        idle = Idle.NAP
        idleCooldowns[Idle.NAP] = System.currentTimeMillis() + NAP_COOLDOWN_MS
        sleeping = true
        sleepingBubble = true
        applyBubble(context.getString(R.string.pet_sleeping), persistent = true)
    }

    private fun wakeUp() {
        if (!sleeping) return
        sleeping = false
        idle = Idle.STAY
        nextDecisionAt = System.currentTimeMillis() + randomIdle()
        if (sleepingBubble) {
            sleepingBubble = false
            applyBubble("", persistent = false)
        }
    }

    /** 记一次互动：重置打盹计时，睡着就叫醒，收起就拉出来。 */
    private fun markInteraction() {
        val now = System.currentTimeMillis()
        lastInteractionAt = now
        mood = mood.touched(now)
        PetMoodStore.write(context, mood)
        untuck()
        wakeUp()
    }

    /** 摸头：缩一下脖子 + 左右轻晃，配合台词；顺便加好感。 */
    private fun patHead() {
        val view = petColumn ?: return
        oneShot = true
        view.animate().cancel()
        view.animate()
            .scaleX(1.06f).scaleY(0.93f).translationY(dpToPx(2).toFloat())
            .setDuration(90)
            .withEndAction {
                view.animate()
                    .scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(180)
                    .withEndAction { oneShot = false }
                    .start()
            }
            .start()
        mood = mood.petted(System.currentTimeMillis())
        PetMoodStore.write(context, mood)
        applyBubble(context.getString(patLine()), persistent = false)
    }

    /** 摸头台词按态度分档：冷淡 / 平常 / 亲近。 */
    private fun patLine(): Int = when {
        mood.isSulking() || mood.level == PetMood.Level.COLD -> R.string.pet_patted_cold
        mood.level == PetMood.Level.NORMAL -> R.string.pet_patted
        else -> R.string.pet_patted_warm
    }

    /** 被戳烦了：脑袋左右甩一下，配一句抱怨。 */
    private fun annoyed() {
        val view = body ?: return
        oneShot = true
        view.animate().cancel()
        view.animate().rotation(7f).setDuration(70)
            .withEndAction {
                view.animate().rotation(-7f).setDuration(90)
                    .withEndAction {
                        view.animate().rotation(0f).setDuration(90)
                            .withEndAction { oneShot = false }
                            .start()
                    }
                    .start()
            }
            .start()
        // 戳太多她会记仇：掉分 + 别扭几分钟
        mood = mood.pokedTooMuch(System.currentTimeMillis())
        PetMoodStore.write(context, mood)
        applyBubble(context.getString(R.string.pet_annoyed), persistent = false)
    }

    /** 长按身体＝抱一下：与摸头同款挤压，换一句台词。 */
    private fun hug() {
        val view = petColumn ?: return
        oneShot = true
        view.animate().cancel()
        view.animate()
            .scaleX(1.05f).scaleY(0.95f).translationY(dpToPx(1).toFloat())
            .setDuration(120)
            .withEndAction {
                view.animate()
                    .scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(220)
                    .withEndAction { oneShot = false }
                    .start()
            }
            .start()
        applyBubble(context.getString(R.string.pet_hug), persistent = false)
    }

    /** 落地：压扁再弹回。 */
    private fun playLanding() {
        val view = petColumn ?: return
        oneShot = true
        view.animate().cancel()
        view.animate()
            .scaleX(1.07f).scaleY(0.9f)
            .setDuration(90)
            .withEndAction {
                view.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(170)
                    .withEndAction { oneShot = false }
                    .start()
            }
            .start()
    }

    // ── 工具 ────────────────────────────────────────────────
    private fun loadBitmaps(size: Size) {
        val target = dpToPx(size.heightDp)
        val loaded = mutableMapOf<String, Bitmap>()
        for (name in listOf("front", "side", "side_walk", "sleep", "drag", "wave")) {
            scaleAsset(name, target)?.let { loaded[name] = it }
        }
        loaded["front"]?.let {
            spriteW = it.width
            spriteH = it.height
        }
        // 探头素材只有上半身，不能按身高归一（会变成巨人）：按画布约定缩放
        if (spriteH > 0) {
            scaleByRatio("peek", spriteH / PEEK_CANON)?.let { loaded["peek"] = it }
        }
        bitmaps = loaded
    }

    /** 按固定比例缩放某个素材（用于不走「按身高归一」的图，如探头素材）。 */
    private fun scaleByRatio(name: String, ratio: Float): Bitmap? {
        return try {
            val decoded = context.assets.open("pet/$name.png").use { BitmapFactory.decodeStream(it) }
                ?: return null
            val w = (decoded.width * ratio).roundToInt().coerceAtLeast(1)
            val h = (decoded.height * ratio).roundToInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(decoded, w, h, true)
            if (scaled !== decoded) decoded.recycle()
            scaled
        } catch (_: Throwable) {
            null
        }
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
        // 归零：重新载入（如改尺寸）时不该再按旧宽度补偿锚点
        spriteW = 0
        spriteH = 0
    }

    private fun randomIdle(): Long = Random.nextLong(IDLE_MIN_MS, IDLE_MAX_MS + 1)

    private fun screenWidth(): Int = context.resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = context.resources.displayMetrics.heightPixels

    private fun dpToPx(dp: Int): Int = (dp * density).roundToInt()

    private fun dpToPxF(dp: Float): Float = dp * density
}
