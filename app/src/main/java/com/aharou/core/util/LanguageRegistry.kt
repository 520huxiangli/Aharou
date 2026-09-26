package com.aharou.core.util

/**
 * App 支持的语言清单。
 *
 * 扩展新语言时只需在此列表追加一项，并新建对应的 res/values-xx/strings.xml，
 * 同步更新 res/xml/locales_config.xml 即可，无需改动其他代码。
 *
 * @property tag BCP-47 language tag。
 * @property displayName 该语言的自述名（用其自身语言书写），用于语言选择列表的展示。
 */
data class SupportedLanguage(
    val tag: String,
    val displayName: String
)

object LanguageRegistry {

    /** 跟随系统的占位值，非真实语言 tag。 */
    const val FOLLOW_SYSTEM = ""

    val languages: List<SupportedLanguage> = listOf(
        SupportedLanguage("zh", "中文"),
        SupportedLanguage("en", "English")
    )

    /** 判断 tag 是否为已支持的真实语言（排除 [FOLLOW_SYSTEM]）。 */
    fun isSupported(tag: String): Boolean = languages.any { it.tag == tag }
}
