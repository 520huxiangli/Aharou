package com.aharou.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 短生命周期节点注册表（自 OpenMinis 的 NodeRegistry 移植）。
 *
 * AccessibilityNodeInfo 跨调用没有稳定标识：`ui dump` 每次都会重新枚举出新对象，
 * 但 Agent 预期几秒后还能用 id 引用同一个节点（如「点 a3f2」）。这里用短 id 缓存
 * 节点引用，[TTL_MS] 过期或服务销毁时清空。
 */
class NodeRegistry {
    private data class Entry(val node: AccessibilityNodeInfo, val createdAt: Long)

    private val map = ConcurrentHashMap<String, Entry>()
    private val seq = AtomicLong(0)

    fun put(node: AccessibilityNodeInfo): String {
        evictExpired()
        val id = nextId()
        map[id] = Entry(node, System.currentTimeMillis())
        return id
    }

    fun get(id: String): AccessibilityNodeInfo? {
        val entry = map[id] ?: return null
        if (System.currentTimeMillis() - entry.createdAt > TTL_MS) {
            map.remove(id)
            return null
        }
        return entry.node
    }

    fun clear() {
        map.clear()
    }

    private fun evictExpired() {
        val now = System.currentTimeMillis()
        val iterator = map.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value.createdAt > TTL_MS) iterator.remove()
        }
    }

    private fun nextId(): String =
        java.lang.Long.toString(seq.incrementAndGet() and 0xFFFFFL, 36).padStart(4, '0')

    companion object {
        const val TTL_MS = 60_000L
    }
}
