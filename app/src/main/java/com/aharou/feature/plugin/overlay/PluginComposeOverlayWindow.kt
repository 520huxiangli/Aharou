package com.aharou.feature.plugin.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.aharou.core.util.FileLogger
import com.aharou.feature.plugin.dsl.ComposeDslNode
import com.aharou.feature.plugin.dsl.ComposeDslRenderResult
import com.aharou.feature.plugin.dsl.RenderComposeDsl
import com.aharou.feature.plugin.presentation.PluginDslBridge

/**
 * 承载 Compose DSL 的浮动窗口。
 *
 * v2 的 `floatingWindows.open({ route })` 给的是 DSL 路由而不是 HTML 页面，
 * 因此不能复用 WebView 版窗口；这里用 ComposeView 渲染，并自己处理拖动与位置。
 */
internal class PluginComposeOverlayWindow(
    context: Context,
    val pluginId: String,
    val overlayId: String,
    private val windowManager: WindowManager,
    private val layoutParams: WindowManager.LayoutParams,
    private val bridge: PluginDslBridge,
    private val routeId: String,
    private val initiallyDraggable: Boolean,
    private val onPositionChanged: (Int, Int) -> Unit
) : FrameLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var attached = false

    /** 渲染结果：null 表示还没拿到，空树表示插件返回了空内容。 */
    private val renderState = mutableStateOf<ComposeDslRenderResult?>(null)
    private val errorState = mutableStateOf<String?>(null)

    private val contentView: ComposeView = ComposeView(context)

    init {
        contentView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        contentView.setContent {
            val result = renderState.value
            val error = errorState.value
            when {
                error != null -> androidx.compose.material3.Text(error)
                result == null -> Unit
                else -> RenderComposeDsl(
                    tree = result.tree,
                    state = result.state,
                    onAction = { actionId, value ->
                        // 浮窗里的点击同样回给插件，插件重渲染后把新树推回来。
                        // dispatch 是 suspend，这里用独立协程跑，不阻塞 Compose 的组合线程。
                        CoroutineScope(Dispatchers.IO).launch {
                            runCatching {
                                val next = bridge.dispatch(routeId, actionId, value)
                                mainHandler.post { renderState.value = next }
                            }.onFailure { failure ->
                                FileLogger.w(TAG, "浮窗动作派发失败: $routeId", failure)
                            }
                        }
                    },
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    scrollStateKey = "overlay:$pluginId:$routeId"
                )
            }
        }
    }

    init {
        addView(contentView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun show() {
        if (attached) return
        runCatching { windowManager.addView(this, layoutParams) }
            .onSuccess { attached = true }
            .onFailure { FileLogger.w(TAG, "浮窗添加失败: $overlayId", it) }
        render()
    }

    fun remove() {
        if (!attached) return
        attached = false
        runCatching { windowManager.removeView(this) }
        runCatching { bridge.close() }
    }

    fun describe() = buildMap<String, Any?> {
        put("overlayId", overlayId)
        put("pluginId", pluginId)
        put("routeId", routeId)
        put("x", layoutParams.x)
        put("y", layoutParams.y)
        put("width", layoutParams.width)
        put("height", layoutParams.height)
        put("kind", "compose_dsl")
    }

    fun moveBy(dx: Int, dy: Int) {
        layoutParams.x += dx
        layoutParams.y += dy
        clampIntoBounds()
        if (attached) runCatching { windowManager.updateViewLayout(this, layoutParams) }
        onPositionChanged(layoutParams.x, layoutParams.y)
    }

    private fun render() {
        // openByRoute 是 suspend：用独立协程跑，拿不到结果时界面上留空而不是崩掉
        CoroutineScope(Dispatchers.Main.immediate).launch {
            runCatching { bridge.openByRoute(routeId) }
                .onSuccess { result -> renderState.value = result }
                .onFailure { error ->
                    FileLogger.w(TAG, "浮窗渲染失败: $routeId", error)
                    errorState.value = error.message ?: error.javaClass.simpleName
                }
        }
    }

    private fun clampIntoBounds() {
        val metrics = context.resources.displayMetrics
        layoutParams.x = layoutParams.x.coerceIn(0, (metrics.widthPixels - layoutParams.width).coerceAtLeast(0))
        layoutParams.y = layoutParams.y.coerceIn(0, (metrics.heightPixels - layoutParams.height).coerceAtLeast(0))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!initiallyDraggable) return super.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                startX = layoutParams.x
                startY = layoutParams.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downX).toInt()
                val dy = (event.rawY - downY).toInt()
                if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                    layoutParams.x = startX + dx
                    layoutParams.y = startY + dy
                    clampIntoBounds()
                    if (attached) runCatching { windowManager.updateViewLayout(this, layoutParams) }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                onPositionChanged(layoutParams.x, layoutParams.y)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0

    private companion object {
        const val TAG = "PluginComposeOverlay"

        fun createLayoutParams(context: Context, width: Int, height: Int, x: Int, y: Int) =
            WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    setCanPlayMoveAnimation(false)
                }
                this.x = x
                this.y = y
            }
    }

    /** 供管理器构造窗口用。 */
    object Factory {
        fun params(context: Context, width: Int, height: Int, x: Int, y: Int) =
            createLayoutParams(context, width, height, x, y)
    }
}
