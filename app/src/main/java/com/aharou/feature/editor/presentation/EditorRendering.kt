package com.aharou.feature.editor.presentation

import android.util.TypedValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * 编辑器配色与绘制相关的零散渲染逻辑，从 `CodeEditorScreen` 拆出来（该文件卡在行数棘轮基线上）。
 * 都只吃 `CodeEditor` 实例，与页面状态无关。
 */

/** 编辑器底色向主题表面色靠拢的比例。 */
private const val EDITOR_THEME_TINT = 0.9f

/**
 * 给编辑器底色掺入当前主题的表面色。
 *
 * TextMate 主题的背景（Dark+ 的 #1E1E1E、Light+ 的纯白）是照 VSCode 的中性色调的，
 * 原样使用会让编辑器在带色温的主题下明显脱离其余界面。保留一成原色以维持编辑区的基调。
 */
internal fun applyThemedBackground(editor: CodeEditor, themeSurface: Color) {
    val scheme = editor.colorScheme
    val base = Color(scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND))
    scheme.setColor(
        EditorColorScheme.WHOLE_BACKGROUND,
        lerp(base, themeSurface, EDITOR_THEME_TINT).toArgb()
    )
}

/** 行号 gutter 背景与编辑区拉开一点亮度差便于区分。基于编辑器背景色自适应，随主题走。 */
internal fun applyLineNumberBackground(editor: CodeEditor, dark: Boolean) {
    val scheme = editor.colorScheme
    val base = Color(scheme.getColor(EditorColorScheme.WHOLE_BACKGROUND))
    val target = if (dark) Color.White else Color.Black
    scheme.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, lerp(base, target, 0.08f).toArgb())
}

/** 把 sp 换算为像素，用于行号左边距等需 px 的 sora API。 */
internal fun spToPx(context: android.content.Context, sp: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics)

/** 根据“显示空白符号”与“显示自动换行箭头”开关合成 sora 的非打印字符绘制标志。 */
internal fun nonPrintableFlags(showWhitespace: Boolean, showWrapArrow: Boolean): Int {
    var flags = 0
    if (showWhitespace) {
        flags = flags or CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or
            CodeEditor.FLAG_DRAW_WHITESPACE_INNER or
            CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING or
            CodeEditor.FLAG_DRAW_WHITESPACE_FOR_EMPTY_LINE or
            CodeEditor.FLAG_DRAW_LINE_SEPARATOR
    }
    if (showWrapArrow) {
        flags = flags or CodeEditor.FLAG_DRAW_SOFT_WRAP
    }
    return flags
}
