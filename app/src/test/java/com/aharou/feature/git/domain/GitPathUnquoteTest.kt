package com.aharou.feature.git.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * git 输出里带引号路径的还原（[unquoteGitPath]）。
 *
 * 重点是八进制转义的多字节还原：git 逐字节输出 `\344\270\255`，若逐段 `toChar()` 就会得到
 * 三个 Latin-1 字符（Git 页中文目录名乱码的成因），必须攒成字节序列后整体按 UTF-8 解码。
 */
class GitPathUnquoteTest {

    @Test
    fun plainPath_returnedAsIs() {
        assertEquals("src/Main.kt", unquoteGitPath("src/Main.kt"))
        assertEquals("中文目录/文件.txt", unquoteGitPath("中文目录/文件.txt"))
    }

    @Test
    fun quotedPath_withSpace() {
        assertEquals("c d.txt", unquoteGitPath("\"c d.txt\""))
    }

    @Test
    fun quotedPath_escapedQuoteAndBackslash() {
        assertEquals("a\"b.txt", unquoteGitPath("\"a\\\"b.txt\""))
        assertEquals("a\\b.txt", unquoteGitPath("\"a\\\\b.txt\""))
    }

    @Test
    fun quotedPath_controlCharEscapes() {
        assertEquals("a\tb.txt", unquoteGitPath("\"a\\tb.txt\""))
        assertEquals("a\nb.txt", unquoteGitPath("\"a\\nb.txt\""))
        assertEquals("a\u0001b.txt", unquoteGitPath("\"a\\001b.txt\""))
    }

    @Test
    fun quotedPath_octalUtf8_decodedAsWholeSequence() {
        assertEquals("中文", unquoteGitPath("\"\\344\\270\\255\\346\\226\\207\""))
        assertEquals(
            "中文目录/文件.txt",
            unquoteGitPath("\"\\344\\270\\255\\346\\226\\207\\347\\233\\256\\345\\275\\225/\\346\\226\\207\\344\\273\\266.txt\"")
        )
    }

    @Test
    fun quotedPath_octalMixedWithAscii() {
        assertEquals("中 文.txt", unquoteGitPath("\"\\344\\270\\255 \\346\\226\\207.txt\""))
        assertEquals("中文\u0001名.txt", unquoteGitPath("\"中文\\001名.txt\""))
    }
}
