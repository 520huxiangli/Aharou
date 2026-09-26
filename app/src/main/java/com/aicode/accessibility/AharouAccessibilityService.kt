package com.aicode.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.aicode.core.util.FileLogger
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Aharou 无障碍服务（自 OpenMinis 的 MinisAccessibilityService 移植·裁剪）。
 *
 * 让 Agent「看得懂、点得动」宿主屏幕：读窗口/节点树、返回键、手势点击/滑动/捏合、
 * 系统级截图、设置节点文本。需用户在系统「无障碍」设置里手动开启（Android 禁止程序化开启）。
 */
class AharouAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AharouA11y"
        private const val EVENT_RING_CAP = 1024

        @Volatile
        private var _instance: AharouAccessibilityService? = null

        fun getInstance(): AharouAccessibilityService? = _instance

        /** 服务是否已连接（设置页展示用）。 */
        fun isConnected(): Boolean = _instance != null
    }

    data class RecordedEvent(
        val type: String,
        val packageName: String?,
        val className: String?,
        val text: String?,
        val timestamp: Long,
    )

    private val eventRing = ConcurrentLinkedQueue<RecordedEvent>()
    private val eventListeners = CopyOnWriteArrayList<(RecordedEvent) -> Unit>()

    val nodeRegistry: NodeRegistry = NodeRegistry()

    override fun onCreate() {
        super.onCreate()
        _instance = this
        FileLogger.i(TAG, "service created")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance = this
        // MIUI 等 ROM 会反复杀掉并重绑本服务；此处留痕便于诊断「服务出现故障」窗口。
        FileLogger.i(TAG, "service connected (manufacturer=${Build.MANUFACTURER})")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val rec = RecordedEvent(
            type = AccessibilityEvent.eventTypeToString(event.eventType),
            packageName = event.packageName?.toString(),
            className = event.className?.toString(),
            text = event.text?.joinToString(" ") { it?.toString() ?: "" }?.takeIf { it.isNotBlank() },
            timestamp = System.currentTimeMillis(),
        )
        eventRing.offer(rec)
        while (eventRing.size > EVENT_RING_CAP) eventRing.poll()
        for (listener in eventListeners) {
            try {
                listener(rec)
            } catch (_: Throwable) {
            }
        }
    }

    override fun onInterrupt() {
        FileLogger.i(TAG, "service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (_instance === this) _instance = null
        eventRing.clear()
        nodeRegistry.clear()
        eventListeners.clear()
        FileLogger.i(TAG, "service destroyed")
    }

    fun snapshotEvents(): List<RecordedEvent> = eventRing.toList()

    fun addEventListener(listener: (RecordedEvent) -> Unit) {
        eventListeners.add(listener)
    }

    fun removeEventListener(listener: (RecordedEvent) -> Unit) {
        eventListeners.remove(listener)
    }

    fun rootNodes(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows ?: emptyList()) {
                w.root?.let { out.add(it) }
            }
        } catch (_: Throwable) {
        }
        if (out.isEmpty()) {
            try {
                rootInActiveWindow?.let { out.add(it) }
            } catch (_: Throwable) {
            }
        }
        return out
    }

    fun windowInfos(): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        try {
            for (w in windows ?: emptyList()) {
                out.add(
                    mapOf(
                        "windowId" to w.id,
                        "type" to when (w.type) {
                            android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
                            android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
                            android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
                            android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "overlay"
                            else -> "other"
                        },
                        "title" to (w.title?.toString() ?: ""),
                        "focused" to w.isFocused,
                        "active" to w.isActive,
                    )
                )
            }
        } catch (_: Throwable) {
        }
        return out
    }

    fun foregroundPackage(): Pair<String?, String?> {
        return try {
            val r = rootInActiveWindow
            (r?.packageName?.toString()) to (r?.className?.toString())
        } catch (_: Throwable) {
            null to null
        }
    }

    fun dispatchSimpleGesture(path: Path, startTime: Long, durationMs: Long, timeoutMs: Long = 5000): Boolean {
        val stroke = GestureDescription.StrokeDescription(path, startTime, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val done = CountDownLatch(1)
        val ok = booleanArrayOf(false)
        val callback = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                ok[0] = true
                done.countDown()
            }

            override fun onCancelled(g: GestureDescription?) {
                ok[0] = false
                done.countDown()
            }
        }
        Handler(Looper.getMainLooper()).post { dispatchGesture(gesture, callback, null) }
        return done.await(timeoutMs, TimeUnit.MILLISECONDS) && ok[0]
    }

    fun dispatchPinch(cx: Float, cy: Float, scale: Float, durationMs: Long): Boolean {
        val initialOffset = 200f
        val finalOffset = (initialOffset * scale).coerceAtLeast(20f)
        val (s0, e0) = if (scale > 1f) {
            (cx - finalOffset to cy) to (cx - initialOffset to cy)
        } else {
            (cx - initialOffset to cy) to (cx - finalOffset to cy)
        }
        val (s1, e1) = if (scale > 1f) {
            (cx + finalOffset to cy) to (cx + initialOffset to cy)
        } else {
            (cx + initialOffset to cy) to (cx + finalOffset to cy)
        }
        val p0 = Path().apply { moveTo(s0.first, s0.second); lineTo(e0.first, e0.second) }
        val p1 = Path().apply { moveTo(s1.first, s1.second); lineTo(e1.first, e1.second) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p0, 0, durationMs))
            .addStroke(GestureDescription.StrokeDescription(p1, 0, durationMs))
            .build()
        val done = CountDownLatch(1)
        val ok = booleanArrayOf(false)
        Handler(Looper.getMainLooper()).post {
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        ok[0] = true
                        done.countDown()
                    }

                    override fun onCancelled(g: GestureDescription?) {
                        ok[0] = false
                        done.countDown()
                    }
                },
                null,
            )
        }
        return done.await(durationMs + 2000, TimeUnit.MILLISECONDS) && ok[0]
    }

    /** 截图结果；成功时 [bitmap] 非空，调用方负责 recycle。 */
    data class ShotResult(
        val bitmap: Bitmap?,
        val errorCode: String? = null,
        val errorMessage: String? = null,
    )

    /** 系统级截图（API 30+）；阻塞至 [timeoutMs]。 */
    @RequiresApi(Build.VERSION_CODES.R)
    fun captureScreenshot(displayId: Int = Display.DEFAULT_DISPLAY, timeoutMs: Long = 5_000): ShotResult {
        val done = CountDownLatch(1)
        val resultRef = java.util.concurrent.atomic.AtomicReference(
            ShotResult(null, "TIMEOUT", "takeScreenshot timed out")
        )
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            takeScreenshot(displayId, executor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    try {
                        val hwBuffer: HardwareBuffer = screenshot.hardwareBuffer
                        val cs: ColorSpace? = screenshot.colorSpace
                        val bmp = Bitmap.wrapHardwareBuffer(hwBuffer, cs)
                        try {
                            hwBuffer.close()
                        } catch (_: Throwable) {
                        }
                        if (bmp == null) {
                            resultRef.set(ShotResult(null, "DECODE_FAILED", "wrapHardwareBuffer returned null"))
                        } else {
                            val sw = bmp.copy(Bitmap.Config.ARGB_8888, false)
                            bmp.recycle()
                            resultRef.set(ShotResult(sw))
                        }
                    } catch (t: Throwable) {
                        resultRef.set(
                            ShotResult(null, "DECODE_FAILED", "${t.javaClass.simpleName}: ${t.message ?: ""}")
                        )
                    } finally {
                        done.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    resultRef.set(ShotResult(null, "TAKE_SCREENSHOT_FAILED", "takeScreenshot error code=$errorCode"))
                    done.countDown()
                }
            })
            if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                FileLogger.w(TAG, "takeScreenshot timed out after ${timeoutMs}ms")
            }
        } catch (t: Throwable) {
            resultRef.set(ShotResult(null, "INTERNAL", "${t.javaClass.simpleName}: ${t.message ?: ""}"))
        } finally {
            executor.shutdown()
        }
        return resultRef.get()
    }

    fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
