package com.aharou.core.soul

import org.junit.Assert.assertEquals
import org.junit.Test

/** [SystemPromptBuilder.languageDirective] 把 SOUL.md 的 `lang` 映射成写给模型的硬约束。 */
class SoulLanguageDirectiveTest {

    @Test
    fun zh_and_en_map_to_directives() {
        assertEquals("Always reply in Chinese (简体中文).", SystemPromptBuilder.languageDirective("zh"))
        assertEquals("Always reply in English.", SystemPromptBuilder.languageDirective("en"))
    }

    @Test
    fun auto_and_unknown_values_produce_no_directive() {
        for (value in listOf("auto", "", "   ", "AUTO", "fr", "zh-CN", "english")) {
            assertEquals("", SystemPromptBuilder.languageDirective(value))
        }
    }

    @Test
    fun matching_is_trimmed_and_case_insensitive() {
        assertEquals("Always reply in Chinese (简体中文).", SystemPromptBuilder.languageDirective(" ZH "))
    }
}
