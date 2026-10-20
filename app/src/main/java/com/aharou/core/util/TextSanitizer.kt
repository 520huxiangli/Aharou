package com.aharou.core.util

/**
 * 记忆正文的注入消毒。
 *
 * 记忆文件是模型自己写的，内容可能来自任何一次对话——包括它读过的网页、别人贴给它的文本。
 * 所以注入前要按不可信输入处理：剥掉零宽/双向控制字符（可用来隐藏内容或伪造换行），
 * 并把形似消息边界或系统标签的片段拆成可见字符，避免一段记忆在系统提示里冒充新指令。
 *
 * 只处理「像协议标签」的短片段，不碰普通的尖括号内容（`<T>` 泛型、HTML 片段照旧保留）。
 */
internal object TextSanitizer {

    fun sanitize(text: String): String {
        if (text.isEmpty()) return text
        var out = INVISIBLE.replace(text, "")
        // `<|im_start|>` 这类特殊 token 的尖括号后面跟竖线，普通标签正则盖不到，单独一遍。
        out = SPECIAL_TOKEN.replace(out) { match ->
            match.value.replace('<', '‹').replace('>', '›')
        }
        return FAKE_TAG.replace(out) { match ->
            match.value.replace('<', '‹').replace('>', '›')
        }
    }

    /** 零宽空格、零宽连接符、双向覆盖、BOM 等不可见控制字符。 */
    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\uFEFF]")

    /** 形如 `<|im_start|>`、`<|endoftext|>` 的特殊 token 包裹形式。 */
    private val SPECIAL_TOKEN = Regex(
        "(?i)<\\|(im_start|im_end|endoftext|system|user|assistant)\\|>"
    )

    /** 形如 `<system>`、`</im_start>`、`<tool_call ...>` 的伪协议标签。 */
    private val FAKE_TAG = Regex(
        "(?i)</?(system|assistant|user|im_start|im_end|tool_call|tool_result|function_call|function_result)[^>]{0,40}>"
    )
}
