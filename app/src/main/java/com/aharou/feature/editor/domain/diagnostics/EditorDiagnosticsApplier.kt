package com.aharou.feature.editor.domain.diagnostics

import com.aharou.core.util.FileLogger
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticDetail
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.style.DiagnosticIndicatorStyle

/**
 * 把 [EditorDiagnostic] 列表画到 sora 编辑器上：波浪线 + 悬停提示。
 *
 * sora 的诊断以**字符下标**（[DiagnosticRegion.startIndex] / [endIndex]）定位，而不是行列，所以这里
 * 负责把 1 基行列换算成下标。脏数据一律裁剪（行号越界、列越界、空行、下标倒挂都跳过），
 * 绝不因为一份不干净的诊断结果让编辑器崩掉。
 *
 * 只能在主线程调用（要读写编辑器的文本与内部诊断容器）。
 */
object EditorDiagnosticsApplier {

    private const val TAG = "EditorDiagnostics"

    /** 应用一批诊断。空列表等价于 [clear]（切到无诊断/不支持的文件时清掉残留波浪线）。 */
    fun apply(editor: CodeEditor, diagnostics: List<EditorDiagnostic>) {
        try {
            if (diagnostics.isEmpty()) {
                clear(editor)
                return
            }
            val text = editor.text
            val regions = ArrayList<DiagnosticRegion>(diagnostics.size)
            diagnostics.forEachIndexed { index, diagnostic ->
                toRegion(text, diagnostic, index.toLong())?.let { regions += it }
            }
            if (regions.isEmpty()) {
                clear(editor)
                return
            }
            editor.diagnosticIndicatorStyle = DiagnosticIndicatorStyle.WAVY_LINE
            editor.setDiagnostics(DiagnosticsContainer().apply { addDiagnostics(regions) })
        } catch (e: Exception) {
            FileLogger.w(TAG, "应用诊断失败", e)
        }
    }

    /** 清空诊断波浪线与悬停提示。 */
    fun clear(editor: CodeEditor) {
        try {
            if (editor.diagnostics != null) editor.setDiagnostics(null)
        } catch (e: Exception) {
            FileLogger.w(TAG, "清除诊断失败", e)
        }
    }

    /** 单条诊断 → sora 诊断区间；任何越界/空行都返回 null 表示丢弃。 */
    private fun toRegion(text: Content, diagnostic: EditorDiagnostic, id: Long): DiagnosticRegion? {
        if (text.lineCount <= 0) return null
        val line = (diagnostic.line - 1).coerceIn(0, text.lineCount - 1)
        val lineLength = text.getColumnCount(line)
        if (lineLength <= 0) return null
        val wholeLine = diagnostic.column < 1 || diagnostic.endColumn <= diagnostic.column
        val startColumn = if (wholeLine) 0 else (diagnostic.column - 1).coerceIn(0, lineLength)
        val endColumn = if (wholeLine) lineLength else (diagnostic.endColumn - 1).coerceIn(startColumn, lineLength)
        if (endColumn <= startColumn) return null
        val startIndex = runCatching { text.getCharIndex(line, startColumn) }.getOrNull() ?: return null
        val endIndex = runCatching { text.getCharIndex(line, endColumn) }.getOrNull() ?: return null
        if (endIndex <= startIndex) return null
        // 只填 briefMessage：detailedMessage 留空（默认 null），sora 的提示窗会自动隐藏小字那一行，
        // 消息只显示一遍。
        val detail = DiagnosticDetail(diagnostic.message)
        return DiagnosticRegion(startIndex, endIndex, diagnostic.severity.soraSeverity(), id, detail)
    }
}

/** 级别映射：sora 的 INFO 档位是 `SEVERITY_TYPO`（NONE=0 / TYPO=1 / WARNING=2 / ERROR=3）。 */
private fun DiagnosticSeverity.soraSeverity(): Short = when (this) {
    DiagnosticSeverity.ERROR -> DiagnosticRegion.SEVERITY_ERROR
    DiagnosticSeverity.WARNING -> DiagnosticRegion.SEVERITY_WARNING
    DiagnosticSeverity.INFO -> DiagnosticRegion.SEVERITY_TYPO
}
