package com.aharou.feature.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JsPromptResult
import android.webkit.JsResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 页面弹出的 alert / confirm / prompt，正在等待用户在面板内处理。
 * 抽到独立文件，方便 [BrowserUseManager] 复用并让宿主界面直接观察。
 */
data class PendingJsDialog(
    val kind: String,
    val message: String,
    val defaultValue: String?,
)

/**
 * [T-android-js-dialogs-256] 网页对话框分流器。
 *
 * 分流规则（对齐 上游 hiddenHost 版语义，但 Aharou 没有 hiddenHost）：
 * - 面板可见（[isUiVisible] 为真）：把对话框挂起并发布到 [pending]，交给用户在面板内处理，
 *   页面 JS 会阻塞到用户选择为止——这正是「用户接管」场景期望的行为。
 * - 面板离屏（AI 后台驱动）：沿用保守默认应答并记账，避免把无人值守的自动化循环卡死：
 *   alert 直接确认、confirm 答 false、prompt 答 null；每次拦截都通过 [record] 记给模型。
 */
class JsDialogController(
    private val isUiVisible: () -> Boolean,
    private val record: (kind: String, message: String, defaultText: String?, defaultResponse: String) -> Unit,
) {

    private companion object {
        const val TAG = "JsDialogController"

        /** 面板内未处理时的自动放行（答否），避免页面 JS 被永久阻塞。 */
        const val DIALOG_TIMEOUT_MS = 30_000L
    }

    private val _pending = MutableStateFlow<PendingJsDialog?>(null)
    val pending: StateFlow<PendingJsDialog?> = _pending.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable { resolve(false, null) }

    private var pendingResult: JsResult? = null
    private var pendingPromptResult: JsPromptResult? = null

    fun alert(message: String, result: JsResult?): Boolean {
        if (!hold("alert", message, null)) {
            record("alert", message, null, "(dismissed)")
            result?.confirm()
        } else {
            pendingResult = result
        }
        return true
    }

    fun confirm(message: String, result: JsResult?): Boolean {
        if (!hold("confirm", message, null)) {
            record("confirm", message, null, "false")
            result?.cancel()
        } else {
            pendingResult = result
        }
        return true
    }

    fun prompt(message: String, defaultValue: String?, result: JsPromptResult?): Boolean {
        if (!hold("prompt", message, defaultValue)) {
            record("prompt", message, defaultValue, "null")
            result?.cancel()
        } else {
            pendingPromptResult = result
        }
        return true
    }

    private fun hold(kind: String, message: String, defaultValue: String?): Boolean {
        if (!isUiVisible()) return false
        handler.removeCallbacks(timeoutRunnable)
        _pending.value = PendingJsDialog(kind, message, defaultValue)
        handler.postDelayed(timeoutRunnable, DIALOG_TIMEOUT_MS)
        return true
    }

    /** 面板侧处理用户的选择。 */
    fun resolve(accept: Boolean, text: String?) {
        val dialog = _pending.value ?: return
        handler.removeCallbacks(timeoutRunnable)
        when (dialog.kind) {
            "prompt" -> if (accept) pendingPromptResult?.confirm(text.orEmpty()) else pendingPromptResult?.cancel()
            "confirm" -> if (accept) pendingResult?.confirm() else pendingResult?.cancel()
            else -> pendingResult?.confirm()
        }
        Log.i(TAG, "[JSDialog] resolved ${dialog.kind} from UI: accept=$accept")
        pendingResult = null
        pendingPromptResult = null
        _pending.value = null
    }
}
