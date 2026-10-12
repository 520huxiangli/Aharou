package com.aharou.feature.agent.domain.mention

/** 输入框「@ 引用工作区文件」候选菜单的状态。 */
sealed interface FileMentionState {
    /** 未激活：当前输入不含正在编辑的 `@` 引用。 */
    data object Hidden : FileMentionState

    /** 正在拉取工作区文件列表。 */
    data object Loading : FileMentionState

    /** 展示候选：[query] 是 `@` 后面的查询词，[files] 是过滤后的相对路径。 */
    data class Visible(val query: String, val files: List<String>) : FileMentionState

    /** 工作区无可枚举文件或枚举失败。 */
    data class Empty(val query: String) : FileMentionState
}

/**
 * 从输入文本里提取正在编辑的 `@` 引用查询词，没有则返回 null。
 *
 * 规避邮箱等误触发：`@` 必须在行首或紧跟空白，前面不能是字母数字（否则 `a@b.com` 会被当成引用）；
 * 且 `@` 之后一直到文本末尾都不能含空白——含空白说明引用 token 已经结束。
 * 取满足条件的最后一个 `@`；输入刚好以 `@` 结尾时返回空串，表示列出全部候选。
 */
fun fileMentionQuery(text: String): String? {
    var searchFrom = text.length - 1
    while (searchFrom >= 0) {
        val at = text.lastIndexOf('@', searchFrom)
        if (at < 0) return null
        val before = if (at == 0) null else text[at - 1]
        if ((before == null || before.isWhitespace()) && text.substring(at + 1).none { it.isWhitespace() }) {
            return text.substring(at + 1)
        }
        searchFrom = at - 1
    }
    return null
}
