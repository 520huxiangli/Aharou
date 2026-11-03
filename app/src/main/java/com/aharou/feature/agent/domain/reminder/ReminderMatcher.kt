package com.aharou.feature.agent.domain.reminder

import com.aharou.core.util.FileLogger
import java.util.concurrent.ConcurrentHashMap

/**
 * 条件求值的纯逻辑部分（不碰文件、不碰 Android 类型），单独拆出来便于单测。
 *
 * 正则统一忽略大小写：规则是给「写错一次就别再犯」用的，命令里的大小写不该影响命中。
 */
internal object ReminderMatcher {

    private val regexCache = ConcurrentHashMap<String, Regex>()

    // 编译失败的正则单独记，否则每次调用都会重试编译并重复刷日志。
    private val invalidPatterns = ConcurrentHashMap.newKeySet<String>()

    fun matchesAll(
        conditions: List<ReminderCondition>,
        mode: MatchMode,
        argsText: String,
        resultText: String,
        isError: Boolean,
    ): Boolean {
        if (conditions.isEmpty()) return false
        val results = conditions.map { matches(it, argsText, resultText, isError) }
        return if (mode == MatchMode.ANY) results.any { it } else results.all { it }
    }

    fun matches(
        condition: ReminderCondition,
        argsText: String,
        resultText: String,
        isError: Boolean,
    ): Boolean = when (condition) {
        is ReminderCondition.ArgsRegex -> regexOf(condition.pattern)?.containsMatchIn(argsText) == true
        is ReminderCondition.ResultRegex -> regexOf(condition.pattern)?.containsMatchIn(resultText) == true
        is ReminderCondition.IsError -> condition.value == isError
        // 嵌套组由调用方按 TriggerCondition / ResolveCondition 单独求值。
        is ReminderCondition.TriggerCondition, is ReminderCondition.ResolveCondition -> false
    }

    private fun regexOf(pattern: String): Regex? {
        regexCache[pattern]?.let { return it }
        if (pattern in invalidPatterns) return null
        return runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }
            .onFailure {
                invalidPatterns += pattern
                FileLogger.w(TAG, "规则提醒正则无效，已跳过：$pattern - ${it.message}")
            }
            .getOrNull()
            ?.also { regexCache[pattern] = it }
    }

    private const val TAG = "ReminderMatcher"
}
