package com.aharou.feature.editor.domain.diagnostics

/**
 * 单条语法诊断。
 *
 * 行列都以 1 为起点，与编译器输出（`File "x.py", line 7`）一致；由
 * [EditorDiagnosticsApplier] 转成 sora 需要的 0 基字符下标，越界一律裁剪。
 *
 * @param line 1 基行号。
 * @param column 1 基起始列。小于 1、或 [endColumn] 不大于它时（列信息缺失）按整行处理。
 * @param endColumn 1 基结束列（不含）。
 * @param severity 级别。
 * @param message 提示文本。
 */
data class EditorDiagnostic(
    val line: Int,
    val column: Int,
    val endColumn: Int,
    val severity: DiagnosticSeverity,
    val message: String,
) {
    companion object {
        /**
         * 整行诊断：检查器只给了行号、没给列（`py_compile` / `luac` 均如此）时用。
         * [column] 与 [endColumn] 相等，[EditorDiagnosticsApplier] 据此铺满整行。
         */
        fun wholeLine(line: Int, severity: DiagnosticSeverity, message: String): EditorDiagnostic =
            EditorDiagnostic(line = line, column = 1, endColumn = 1, severity = severity, message = message)
    }
}
