package com.aharou.feature.browser.presentation

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Composable wrapper around Android WebView, mirroring iOS BrowserWebView.
 *
 * [T-android-browser-blank] Hosts the WebView inside a FrameLayout container
 * and swaps it in `update` instead of returning the WebView from `factory`.
 * AndroidView's factory runs ONCE per node — with the old direct-return
 * approach, switching tabs (or the pool recreating a WebView after idle
 * eviction) recomposed with a NEW WebView instance but the screen kept
 * showing the stale one: the sheet header/URL bar (StateFlows from the
 * current tab manager) looked correct while the content area rendered a
 * dead/blank WebView — the reported black/white screen. iOS avoids this via
 * `.id(pool.selectedTabId)` remounting; the container+update pattern is the
 * Android equivalent.
 *
 * Ask all ancestor views (including the host ModalBottomSheet) to stop
 * intercepting touches as soon as the user puts a finger down on the
 * WebView, so page scroll gestures never drag the sheet.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun BrowserWebView(
    webView: WebView,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context -> FrameLayout(context) },
        update = { container ->
            val mounted = container.getChildAt(0)
            if (mounted !== webView) {
                container.removeAllViews()
                // The WebView may still be parented to a previous container
                // (sheet re-open racing the old node's disposal) — detach
                // first or addView throws "child already has a parent".
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN,
                        MotionEvent.ACTION_MOVE,
                        MotionEvent.ACTION_POINTER_DOWN ->
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL ->
                            v.parent?.requestDisallowInterceptTouchEvent(false)
                    }
                    false
                }
                container.addView(
                    webView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                // WebView 刚被 re-parent/重新挂载时，之前 detach 掉的硬件层已失效，
                // 且尺寸没变就不会触发 onSizeChanged、也就没有新帧 → 会一直停在空白。
                // 主动请求一次布局与重绘，把它拉回可见状态。
                webView.requestLayout()
                webView.invalidate()
                // 上面那次 invalidate 在尚未 attach 到新窗口时会被丢弃（换窗口后旧硬件层已作废），
                // 而静态页面没人再触发重绘 —— 只能等下一次滚动/动画才突然出画面（「空屏一段」）。
                // 因此再排一帧，等真正 attach 到新窗口后补一次重绘。
                webView.post {
                    if (webView.isAttachedToWindow) {
                        webView.requestLayout()
                        webView.invalidate()
                    }
                }
            }
        },
        onRelease = { container ->
            // 只摘“自己容器里就是它”的情况：多宿主过渡时被 dispose 的那个容器
            // 不该把 WebView 从还活着的宿主手里抢走（那会让幸存者直接空白）。
            val child = container.getChildAt(0)
            if (child === webView) container.removeView(child)
        },
        modifier = modifier,
    )
}
