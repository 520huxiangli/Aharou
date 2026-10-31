package com.aharou.feature.editor.presentation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.aharou.core.util.FileLogger
import com.aharou.feature.editor.domain.diagnostics.DiagnosticSeverity
import com.aharou.feature.editor.domain.diagnostics.EditorDiagnostic
import io.github.rosemoe.sora.widget.CodeEditor

/** 提示文字相对行高的比例：略小一号，不与代码本身抢注意力。 */
private const val HINT_TEXT_RATIO = 0.72f

/** 提示与行尾代码之间的间距（dp）。 */
private const val HINT_GAP_DP = 8f

/** 剩余宽度小于它（dp）就干脆不画，避免只露出半个字符。 */
private const val MIN_HINT_WIDTH_DP = 48f

/**
 * 与编辑器配色无关的固定语义色：sora 的 colorScheme 没有「诊断级别」这一档可取的键，
 * 而这三个值与深浅主题下的代码前景都有足够对比度。
 */
private const val COLOR_ERROR = 0xFFF14C4C.toInt()
private const val COLOR_WARNING = 0xFFCCA700.toInt()
private const val COLOR_INFO = 0xFF8A8A8A.toInt()

private const val TAG = "InlineHintCodeEditor"

/**
 * 带「行尾诊断」的编辑器：在问题行末尾追加一条简短的提示文字（Error Lens 效果）。
 *
 * 为什么子类化而不是套 Compose 覆盖层：覆盖层要每帧跨 View/Compose 同步滚动量，抖动风险高；
 * 这里在 [onDraw] 末尾直接往同一块 Canvas 上画，滚动与重绘天然跟编辑器一致。
 *
 * 数据不走 sora 内部的诊断容器（那是字符下标，且没有公开的枚举接口），而是由
 * `EditorDiagnostic` 列表直接喂进来——它本来就带着 1 基行号，正好是这里需要的东西。
 *
 * 任何绘制异常都吞掉：画不出一条提示是小事，让编辑器崩掉是大事。
 */
class InlineHintCodeEditor(context: Context) : CodeEditor(context) {

    /**
     * 要显示在行尾的诊断。按行归并、只留最严重的一条：同一行堆三四条提示会把代码挤没，
     * 而用户真正要先看的永远是 ERROR。
     */
    var inlineHints: List<EditorDiagnostic> = emptyList()
        set(value) {
            if (field == value) return
            field = value
            hintsByLine = value
                .groupBy { it.line - 1 }
                .mapValues { (_, list) -> list.minByOrNull { it.severity.ordinal } ?: list.first() }
            invalidate()
        }

    /** 行号（0 基）→ 该行要显示的诊断；空表示不画。 */
    private var hintsByLine: Map<Int, EditorDiagnostic> = emptyMap()

    private val hintPaint = TextPaint().apply {
        isAntiAlias = true
        typeface = Typeface.MONOSPACE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (hintsByLine.isEmpty()) return
        try {
            drawHints(canvas)
        } catch (e: Exception) {
            FileLogger.w(TAG, "行尾诊断绘制失败", e)
        }
    }

    private fun drawHints(canvas: Canvas) {
        val editorLayout = layout ?: return
        val content = text ?: return
        if (content.lineCount <= 0) return

        val hintSize = rowHeight * HINT_TEXT_RATIO
        if (hintSize <= 0f) return
        hintPaint.textSize = hintSize

        val density = resources.displayMetrics.density
        val gap = HINT_GAP_DP * density
        val minWidth = MIN_HINT_WIDTH_DP * density

        // 内容区左边界：行号栏右侧。超出这个范围的绘制要裁掉，否则提示会压到行号上。
        val contentLeft = offsetX.toFloat()
        val scrollX = scroller.currX
        val scrollY = scroller.currY

        canvas.save()
        canvas.clipRect(contentLeft, 0f, width.toFloat(), height.toFloat())
        try {
            hintsByLine.forEach { (line, diagnostic) ->
                if (line < 0 || line >= content.lineCount) return@forEach
                val lineLength = content.getColumnCount(line)
                // getCharLayoutOffset 的 [0] 是该行 row 的底部 y、[1] 是列位置的 x。
                val offsets = editorLayout.getCharLayoutOffset(line, lineLength)
                if (offsets.size < 2) return@forEach
                val rowBottomY = offsets[0].toFloat()
                val endX = offsets[1].toFloat()

                val screenY = offsetY - scrollY + rowBottomY
                if (screenY < 0f || screenY > height) return@forEach
                val screenX = contentLeft - scrollX + endX + gap
                if (screenX < contentLeft) return@forEach

                val available = width - screenX - gap
                if (available < minWidth) return@forEach

                // y 是行底部，基线要往上收，让文字在行内垂直居中。
                val baseline = screenY - (rowHeight - hintSize) / 2f
                hintPaint.color = diagnostic.severity.hintColor()
                val label = diagnostic.message.replace('\n', ' ')
                val fitted = TextUtils.ellipsize(
                    label,
                    hintPaint,
                    available,
                    TextUtils.TruncateAt.END
                ).toString()
                if (fitted.isEmpty()) return@forEach
                canvas.drawText(fitted, screenX, baseline, hintPaint)
            }
        } finally {
            canvas.restore()
        }
    }
}

/** 级别 → 提示文字颜色。INFO 用灰色：它本就是可忽略的一类。 */
private fun DiagnosticSeverity.hintColor(): Int = when (this) {
    DiagnosticSeverity.ERROR -> COLOR_ERROR
    DiagnosticSeverity.WARNING -> COLOR_WARNING
    DiagnosticSeverity.INFO -> COLOR_INFO
}
