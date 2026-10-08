package com.aharou.feature.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FileBrowserViewModel.formatDate：列表行日期文本的守卫分支。
 *
 * 只测这个纯伴生函数（不实例化 ViewModel）：0 与负时间戳必须返回占位符「—」而不是格式化出
 * 1970 年，正时间戳返回含四位年份的日期串。日期串本身随 locale/时区变化，故对正样本只做
 * 年份形状断言。
 */
class FileBrowserDateFormatTest {

    @Test
    fun formatDate_nonPositiveTimestamp_returnsPlaceholder() {
        assertEquals("—", FileBrowserViewModel.formatDate(0L))
        assertEquals("—", FileBrowserViewModel.formatDate(-1L))
    }

    @Test
    fun formatDate_positiveTimestamp_returnsDateWithYear() {
        val text = FileBrowserViewModel.formatDate(1_700_000_000_000L)

        assertFalse(text == "—")
        assertTrue(Regex("\\d{4}").containsMatchIn(text))
    }
}
