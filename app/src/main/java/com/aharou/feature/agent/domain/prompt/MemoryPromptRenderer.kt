package com.aharou.feature.agent.domain.prompt

import com.aharou.feature.agent.domain.memory.Memory
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.domain.memory.MemoryType

internal object MemoryPromptRenderer {
    const val MAX_INDEX_CHARS = 8_000

    private const val GLOBAL_HEADER = "全局记忆 (跨项目个人偏好，需要详情时用 memory(action=read, name=xxx, scope=global))："
    private const val PROJECT_HEADER = "项目记忆 (当前项目专属，需要详情时用 memory(action=read, name=xxx, scope=project))："
    private const val OMISSION_NOTICE = "部分记忆索引因 8000 字符总预算未注入；memory(action=list) 仍可查看完整清单，再用 memory(action=read, name=..., scope=...) 读取正文。"

    fun render(memories: List<Memory>): String {
        val full = renderSelected(memories)
        if (full.length <= MAX_INDEX_CHARS) return full

        val selected = mutableSetOf<Int>()
        val candidates = memories.indices.sortedBy { if (isCore(memories[it])) 0 else 1 }
        val indexBudget = MAX_INDEX_CHARS - OMISSION_NOTICE.length - 2
        for (index in candidates) {
            selected.add(index)
            val content = renderSelected(memories.filterIndexed { i, _ -> i in selected })
            if (content.length > indexBudget) selected.remove(index)
        }
        val content = renderSelected(memories.filterIndexed { i, _ -> i in selected })
        return if (content.isEmpty()) OMISSION_NOTICE else "$content\n\n$OMISSION_NOTICE"
    }

    fun expandOnce(text: String, variable: String, content: String?): String {
        val first = text.indexOf(variable)
        if (first < 0) return text
        return text.substring(0, first) + content.orEmpty() +
            text.substring(first + variable.length).replace(variable, "")
    }

    private fun renderSelected(memories: List<Memory>): String = buildString {
        for (scope in MemoryScope.entries) {
            val scoped = memories.filter { it.scope == scope }
            if (scoped.isEmpty()) continue
            if (isNotEmpty()) append("\n\n")
            append(if (scope == MemoryScope.GLOBAL) GLOBAL_HEADER else PROJECT_HEADER)
            scoped.forEach { append("\n- ${it.name}: ${it.description.ifBlank { "无" }}") }
        }
    }

    private fun isCore(memory: Memory): Boolean {
        if (memory.type == MemoryType.IDENTITY) return true
        val base = memory.name.substringBeforeLast('.')
        return base.isNotEmpty() && base.all { it.isUpperCase() || it.isDigit() || it == '_' }
    }
}
