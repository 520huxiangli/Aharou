package com.aharou.feature.voice.assistant

import android.app.assist.AssistStructure
import android.content.Context
import android.graphics.Bitmap
import android.text.InputType

/**
 * 系统助手唤起时拿到的「当前屏幕」。
 *
 * 三个来源互相独立：视图层级里的文本、前台应用信息、屏幕截图。系统可以只给其中一部分
 * （用户在系统设置里关掉截图、或者前台应用拒绝提供视图结构），所以每个字段都可空。
 */
internal data class ScreenContext(
    val packageName: String? = null,
    val appLabel: String? = null,
    val text: String = "",
    val screenshot: Bitmap? = null,
    val updatedAtMs: Long = 0L,
) {
    val hasText: Boolean get() = text.isNotBlank()
    val hasScreenshot: Boolean get() = screenshot != null
}

/**
 * 把 [AssistStructure] 摊平成一段可读文本。
 *
 * 只取用户看得见的东西（text / contentDescription / hint），不取 id、坐标这类无障碍无关数据；
 * 密码类输入框整段跳过，避免把用户正在输入的凭据带进模型上下文。
 */
internal object AssistStructureReader {

    /** 文本上限：浮层里只是给模型一个「你在看什么」的提示，不需要把整页长文塞进上下文。 */
    const val MAX_CHARS = 4_000

    fun readText(structure: AssistStructure?, maxChars: Int = MAX_CHARS): String {
        if (structure == null) return ""
        val out = LinkedHashSet<String>()
        var total = 0
        for (i in 0 until structure.windowNodeCount) {
            val root = structure.getWindowNodeAt(i)?.rootViewNode ?: continue
            total = collect(root, out, total, maxChars)
            if (total >= maxChars) break
        }
        return out.joinToString("\n")
    }

    private fun collect(
        node: AssistStructure.ViewNode,
        out: MutableSet<String>,
        current: Int,
        maxChars: Int,
    ): Int {
        var total = current
        val inputType = node.inputType
        val sensitive = isSensitiveInputType(inputType)
        if (!sensitive) {
            appendLine(out, node.text?.toString()) { total += it }
            appendLine(out, node.contentDescription?.toString()) { total += it }
            appendLine(out, node.hint?.toString()) { total += it }
        }
        if (total >= maxChars) return total
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) ?: continue
            total = collect(child, out, total, maxChars)
            if (total >= maxChars) break
        }
        return total
    }

    private inline fun appendLine(out: MutableSet<String>, raw: String?, onAdded: (Int) -> Unit) {
        val line = raw?.replace('\n', ' ')?.trim().orEmpty()
        if (line.isEmpty()) return
        if (out.add(line)) onAdded(line.length)
    }

    /**
     * 密码类输入框：除系统自己剥掉的敏感文本外再兜一层。
     *
     * `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` 虽然看得见明文，但语义上仍是密码输入，
     * 不因为「用户此刻能看见」就把它当普通文本送出去。
     */
    fun isSensitiveInputType(inputType: Int): Boolean {
        if (inputType == 0) return false
        val classBits = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (classBits) {
            InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    /** 前台应用的显示名；查不到就用包名。 */
    fun appLabel(context: Context, packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }
}
