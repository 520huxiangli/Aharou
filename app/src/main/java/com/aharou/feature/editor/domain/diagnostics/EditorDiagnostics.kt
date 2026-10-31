package com.aharou.feature.editor.domain.diagnostics

import android.content.Context
import com.aharou.feature.editor.presentation.InlineHintCodeEditor
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.rosemoe.sora.widget.CodeEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 语法诊断的接线入口：一次调用完成「跑检查（IO）→ 应用波浪线（主线程）」。
 *
 * 给 `CodeEditorScreen` 用的一行式接线（在 `val context = LocalContext.current` 之后）：
 * ```
 * LaunchedEffect(activePath, editorRef.value, baselineText.value) {
 *     EditorDiagnostics.refresh(context, editorRef.value, activePath, baselineText.value.length)
 * }
 * ```
 * `baselineText` 只在「刚加载完」（EditorSurface 里 `LaunchedEffect(state.content)`）与
 * 「刚保存成功」两处被改写，把它当 key 正好命中打开 / 保存两个触发点，不必额外维护标志位。
 *
 * 为何自带 EntryPoint 取单例：[CodeEditorScreen] 是 Composable，拿不到注入对象；而本 feature 的
 * 写入范围限定在该 diagnostics 目录，helper 只能放这里。运行期与 ViewModel 注入等价。
 */
object EditorDiagnostics {

    /**
     * 对 [path] 指向的文件跑一次语法检查并应用到 [editor]。
     *
     * [editor] 为 null（编辑器尚未创建）时直接返回；[contentLength] 为编辑器内字符数，用于体积阈值。
     * [lspAttached] 为 true 时不再跑轻量检查（诊断由 editor-lsp 自己推），只清掉行尾提示。
     * [errorLens] 控制是否把同一份诊断再画成行尾文字。
     * 必须在协程中调用；内部自行切 IO 跑命令、切主线程应用诊断。
     */
    suspend fun refresh(
        context: Context,
        editor: CodeEditor?,
        path: String,
        contentLength: Int,
        lspAttached: Boolean = false,
        errorLens: Boolean = true,
    ) {
        val target = editor ?: return
        if (lspAttached) {
            // LSP 的诊断由 editor-lsp 直接推给编辑器，app 侧拿不到列表，行尾提示无从生成。
            // 切到有语言服务器的文件时，要把上一个文件留下的行尾提示清掉。
            withContext(Dispatchers.Main.immediate) {
                (target as? InlineHintCodeEditor)?.inlineHints = emptyList()
            }
            return
        }
        val runner = EntryPointAccessors.fromApplication(
            context.applicationContext,
            EditorDiagnosticsEntryPoint::class.java,
        ).diagnosticsRunner()
        val diagnostics = withContext(Dispatchers.IO) {
            runner.check(path, contentLength)
        }
        withContext(Dispatchers.Main.immediate) {
            EditorDiagnosticsApplier.apply(target, diagnostics)
            // 行尾提示与波浪线共用同一份诊断：关掉开关时给空列表，避免旧提示残留。
            (target as? InlineHintCodeEditor)?.inlineHints = if (errorLens) diagnostics else emptyList()
        }
    }
}

/** Composable 拿不到注入单例（没有 ViewModel 入口），只能走 EntryPoint。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface EditorDiagnosticsEntryPoint {
    fun diagnosticsRunner(): DiagnosticsRunner
}
