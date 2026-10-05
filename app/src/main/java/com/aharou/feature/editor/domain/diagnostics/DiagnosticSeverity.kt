package com.aharou.feature.editor.domain.diagnostics

/**
 * 语法诊断级别。
 *
 * 只保留三级，与 sora 诊断 API 的 severity 常量对应关系见
 * [EditorDiagnosticsApplier]（ERROR→SEVERITY_ERROR、WARNING→SEVERITY_WARNING、INFO→SEVERITY_TYPO）。
 */
enum class DiagnosticSeverity {
    ERROR,
    WARNING,
    INFO,
}
