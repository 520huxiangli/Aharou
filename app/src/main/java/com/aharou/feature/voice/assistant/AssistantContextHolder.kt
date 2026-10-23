package com.aharou.feature.voice.assistant

import android.app.assist.AssistStructure
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 助手会话（系统回调）→ 浮层界面（Activity）之间的进程内传递。
 *
 * 系统把视图结构与截图分两次回调投递，且都晚于 onShow，所以界面先打开、数据后到；
 * 这里用 StateFlow 让界面自己等，不靠固定延时猜测。
 *
 * 只在主进程内用，不做持久化：截图是用户当前的隐私画面，会话结束就丢。
 */
internal object AssistantContextHolder {

    private val _context = MutableStateFlow(ScreenContext())
    val context: StateFlow<ScreenContext> = _context.asStateFlow()

    /** 每次唤起先清掉上一次的残留，避免把无关应用的截图当成这次的上下文。 */
    fun reset() {
        _context.value.screenshot?.recycle()
        _context.value = ScreenContext()
    }

    fun updateAssist(structure: AssistStructure?, appLabel: String?) {
        val packageName = structure?.activityComponent?.packageName
        val text = runCatching { AssistStructureReader.readText(structure) }.getOrDefault("")
        _context.update { current ->
            current.copy(
                packageName = packageName ?: current.packageName,
                appLabel = appLabel ?: current.appLabel,
                text = text,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    /** 磁贴/电源键路径的文本（来自 Shizuku 的 uiautomator dump）。 */
    fun updateText(text: String, appLabel: String?, packageName: String?) {
        _context.update { current ->
            current.copy(
                text = text,
                appLabel = appLabel ?: current.appLabel,
                packageName = packageName ?: current.packageName,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    /** 界面已把截图编码成附件，这份位图就没用了，及早还给系统。 */
    fun updateScreenshot(bitmap: Bitmap?) {
        if (bitmap == null) return
        _context.update { current ->
            current.screenshot?.recycle()
            current.copy(screenshot = bitmap, updatedAtMs = System.currentTimeMillis())
        }
    }

    fun releaseScreenshot() {
        _context.update { current ->
            current.screenshot?.recycle()
            current.copy(screenshot = null)
        }
    }

    fun clear() {
        _context.value.screenshot?.recycle()
        _context.value = ScreenContext()
    }
}
