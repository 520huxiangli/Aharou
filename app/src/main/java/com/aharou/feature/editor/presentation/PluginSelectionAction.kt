package com.aharou.feature.editor.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import com.aharou.feature.plugin.domain.PluginUiEntry
import com.aharou.feature.plugin.presentation.PluginEntryContext
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.component.EditorTextActionWindow

/** 选区文本进插件上下文前的上限：插件页面只做轻量处理，没必要把整份大文件塞过去。 */
private const val MAX_PLUGIN_SELECTION_CHARS = 16_000

/**
 * 把插件动作挂到选中文本的浮动菜单。
 *
 * 编辑器重建时才替换组件，入口与回调通过 [rememberUpdatedState] 随组合更新——
 * 直接闭包捕获会在切文件后仍指向旧数据。
 */
@Composable
internal fun PluginSelectionAction(
    editor: CodeEditor?,
    path: String,
    entries: List<PluginUiEntry>,
    languageTag: String,
    onEntry: (PluginUiEntry, PluginEntryContext) -> Unit
) {
    val latestEntries = rememberUpdatedState(entries)
    val latestLanguage = rememberUpdatedState(languageTag)
    val latestOnEntry = rememberUpdatedState(onEntry)
    val latestPath = rememberUpdatedState(path)
    LaunchedEffect(editor) {
        val host = editor ?: return@LaunchedEffect
        host.replaceComponent(
            EditorTextActionWindow::class.java,
            PluginTextActionWindow(
                hostEditor = host,
                entriesProvider = { latestEntries.value },
                languageTagProvider = { latestLanguage.value },
                contextProvider = { buildPluginEntryContext(host, latestPath.value) },
                onEntry = { entry, ctx -> latestOnEntry.value(entry, ctx) }
            )
        )
    }
}

private fun buildPluginEntryContext(editor: CodeEditor, path: String): PluginEntryContext {
    val cursor = editor.cursor
    val left = cursor.left()
    val right = cursor.right()
    val selected = if (cursor.isSelected && left != null && right != null) {
        val from = minOf(left.index, right.index)
        val to = minOf(maxOf(left.index, right.index), from + MAX_PLUGIN_SELECTION_CHARS)
        runCatching { editor.text.substring(from, to) }.getOrNull()
    } else {
        null
    }
    return PluginEntryContext(
        path = path,
        selection = selected,
        language = path.substringAfterLast('.', "").ifBlank { null },
        lineStart = if (selected != null && left != null && right != null) {
            minOf(left.line, right.line) + 1
        } else null,
        lineEnd = if (selected != null && left != null && right != null) {
            maxOf(left.line, right.line) + 1
        } else null
    )
}
