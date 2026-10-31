package com.aharou.feature.agent.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「显示全部」的数据源：注入快照只留最近一轮、按会话隔离、空值不落（纯 JVM）。
 *
 * 这里要防的是界面出现空白面板（记了空串）与跨会话串台（A 会话看到 B 会话的注入）。
 */
class InjectedContextStoreTest {

    @Test
    fun `按会话隔离记录`() {
        val store = InjectedContextStore()
        store.record("s1", "aaa")
        store.record("s2", "bbb")
        assertEquals("aaa", store.bySession.value["s1"])
        assertEquals("bbb", store.bySession.value["s2"])
    }

    @Test
    fun `同一会话只保留最近一轮`() {
        val store = InjectedContextStore()
        store.record("s1", "第一轮")
        store.record("s1", "第二轮")
        assertEquals("第二轮", store.bySession.value["s1"])
        assertEquals(1, store.bySession.value.size)
    }

    @Test
    fun `空正文与空会话号不登记`() {
        val store = InjectedContextStore()
        store.record("s1", "")
        store.record(null, "正文")
        store.record("  ", "正文")
        assertEquals(emptyMap<String, String>(), store.bySession.value)
        assertNull(store.bySession.value["s1"])
    }

    @Test
    fun `忘记会话后不再返回旧快照`() {
        val store = InjectedContextStore()
        store.record("s1", "aaa")
        store.forget("s1")
        assertNull(store.bySession.value["s1"])
    }
}
