package com.aharou.feature.connectors.domain

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * 双语文本：按用户当前语言取一版，缺一版时回退到另一版。
 *
 * 原先定义在旧「浏览连接器」目录的模型文件里；该目录页下线后，只有这个类型仍被连接器清单
 * 使用（`Connector.about`），故随连接器一起搬到本包，避免为一个类型保留整个已死的模型文件。
 */
@Serializable
data class McpLocalizedText(val zh: String = "", val en: String = "") {
    fun resolve(languageTag: String?): String {
        val zhPreferred = (languageTag?.takeIf { it.isNotBlank() } ?: Locale.getDefault().language)
            .startsWith("zh", ignoreCase = true)
        val primary = if (zhPreferred) zh else en
        return primary.ifBlank { if (zhPreferred) en else zh }
    }
}
