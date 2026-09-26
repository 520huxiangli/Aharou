package com.aharou.feature.terminal.domain.model

import androidx.annotation.StringRes
import com.aharou.R

/**
 * 终端预设配色主题（精选 5 套热门主题，含默认 Termius Dark 与亮白主题）。
 *
 * @param id 唯一标识符，持久化于 DataStore
 * @param nameRes 国际化名称资源
 * @param background 背景颜色 ARGB
 * @param foreground 前景默认文字颜色 ARGB
 * @param cursor 光标颜色 ARGB
 * @param ansiColors 16 色标准 ANSI 调色板（0~15：黑、红、绿、黄、蓝、洋红、青、白 及对应亮色）
 */
data class TerminalThemePreset(
    val id: String,
    @param:StringRes val nameRes: Int,
    val background: Int,
    val foreground: Int,
    val cursor: Int,
    val ansiColors: IntArray = IntArray(16)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TerminalThemePreset
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    companion object {
        /** 默认主题：经典 Termius 深邃蓝黑底 + 亮绿光标。 */
        val TERMIUS_DARK = TerminalThemePreset(
            id = "termius_dark",
            nameRes = R.string.terminal_theme_termius_dark,
            background = 0xFF141923.toInt(),
            foreground = 0xFFD8DEE9.toInt(),
            cursor = 0xFF35E28B.toInt(),
            ansiColors = intArrayOf(
                0xFF1B222D.toInt(), 0xFFE05252.toInt(), 0xFF35E28B.toInt(), 0xFFF0AF39.toInt(),
                0xFF4390F7.toInt(), 0xFFB362FF.toInt(), 0xFF2ED1D8.toInt(), 0xFFD8DEE9.toInt(),
                0xFF4C566A.toInt(), 0xFFFF6E6E.toInt(), 0xFF5DFFA9.toInt(), 0xFFFFC766.toInt(),
                0xFF6CA8FF.toInt(), 0xFFCA8BFF.toInt(), 0xFF5DF2F9.toInt(), 0xFFECEFF4.toInt()
            )
        )

        /** Dracula：官方标准极客暗黑主题（基于 draculatheme.com 规范）。 */
        val DRACULA = TerminalThemePreset(
            id = "dracula",
            nameRes = R.string.terminal_theme_dracula,
            background = 0xFF282A36.toInt(),
            foreground = 0xFFF8F8F2.toInt(),
            cursor = 0xFF50FA7B.toInt(),
            ansiColors = intArrayOf(
                0xFF21222C.toInt(), 0xFFFF5555.toInt(), 0xFF50FA7B.toInt(), 0xFFF1FA8C.toInt(),
                0xFFBD93F9.toInt(), 0xFFFF79C6.toInt(), 0xFF8BE9FD.toInt(), 0xFFF8F8F2.toInt(),
                0xFF6272A4.toInt(), 0xFFFF6E6E.toInt(), 0xFF69FF94.toInt(), 0xFFFFFFA5.toInt(),
                0xFFD6ACFF.toInt(), 0xFFFF92DF.toInt(), 0xFFA4FFFF.toInt(), 0xFFFFFFFF.toInt()
            )
        )

        /** One Dark：Atom / VSCode 经典暗色。 */
        val ONE_DARK = TerminalThemePreset(
            id = "one_dark",
            nameRes = R.string.terminal_theme_one_dark,
            background = 0xFF282C34.toInt(),
            foreground = 0xFFABB2BF.toInt(),
            cursor = 0xFF528BFF.toInt(),
            ansiColors = intArrayOf(
                0xFF282C34.toInt(), 0xFFE06C75.toInt(), 0xFF98C379.toInt(), 0xFFE5C07B.toInt(),
                0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFABB2BF.toInt(),
                0xFF5C6370.toInt(), 0xFFE06C75.toInt(), 0xFF98C379.toInt(), 0xFFE5C07B.toInt(),
                0xFF61AFEF.toInt(), 0xFFC678DD.toInt(), 0xFF56B6C2.toInt(), 0xFFFFFFFF.toInt()
            )
        )

        /** Monokai：经典活力暗色（基于 Monokai Classic 规范）。 */
        val MONOKAI = TerminalThemePreset(
            id = "monokai",
            nameRes = R.string.terminal_theme_monokai,
            background = 0xFF272822.toInt(),
            foreground = 0xFFF8F8F2.toInt(),
            cursor = 0xFFA6E22E.toInt(),
            ansiColors = intArrayOf(
                0xFF272822.toInt(), 0xFFF92672.toInt(), 0xFFA6E22E.toInt(), 0xFFE6DB74.toInt(),
                0xFF66D9EF.toInt(), 0xFFAE81FF.toInt(), 0xFFA1EFE4.toInt(), 0xFFF8F8F2.toInt(),
                0xFF75715E.toInt(), 0xFFF92672.toInt(), 0xFFA6E22E.toInt(), 0xFFE6DB74.toInt(),
                0xFF66D9EF.toInt(), 0xFFAE81FF.toInt(), 0xFFA1EFE4.toInt(), 0xFFF9F8F5.toInt()
            )
        )

        /** GitHub Dark：官方标准深黑底主题（基于 GitHub Primer Dark Default 规范）。 */
        val GITHUB_DARK = TerminalThemePreset(
            id = "github_dark",
            nameRes = R.string.terminal_theme_github_dark,
            background = 0xFF0D1117.toInt(),
            foreground = 0xFFE6EDF3.toInt(),
            cursor = 0xFF2F81F7.toInt(),
            ansiColors = intArrayOf(
                0xFF484F58.toInt(), 0xFFFF7B72.toInt(), 0xFF3FB950.toInt(), 0xFFD29922.toInt(),
                0xFF58A6FF.toInt(), 0xFFBC8CFF.toInt(), 0xFF39C5CF.toInt(), 0xFFB1BAC4.toInt(),
                0xFF6E7681.toInt(), 0xFFFFA198.toInt(), 0xFF56D364.toInt(), 0xFFE3B341.toInt(),
                0xFF79C0FF.toInt(), 0xFFD2A8FF.toInt(), 0xFF56D4DD.toInt(), 0xFFFFFFFF.toInt()
            )
        )

        val ALL_PRESETS = listOf(
            TERMIUS_DARK,
            DRACULA,
            ONE_DARK,
            MONOKAI,
            GITHUB_DARK
        )

        fun findById(id: String): TerminalThemePreset =
            ALL_PRESETS.firstOrNull { it.id == id }
                ?: TERMIUS_DARK
    }
}
