package com.aharou.feature.agent.domain.reminder

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 规则提醒配置文件的顶层容器。
 *
 * 三层优先级:项目级 `.aharou/reminders.json` 覆盖全局级 `~/.aharou/reminders.json`,
 * 两者都不存在时回退内置默认 `assets/reminders.default.json`。同 id 条目项目级覆盖全局级。
 */
@Serializable
data class ReminderConfigFile(
    val reminders: List<ReminderRule> = emptyList()
)

/**
 * 一条提醒规则:触发条件、消息正文与参考文档索引。
 *
 * @param id 全局唯一标识符,用于项目级覆盖全局级规则
 * @param enabled 开关,默认启用
 * @param layer 工作层次: tool=工具执行后检查,turn_end=轮次即将结束时检查
 * @param tools 作用工具名列表,空列表表示对全部工具生效
 * @param conditions 触发判定条件列表
 * @param match 条件组合方式: all=全部命中才触发,any=任一命中即触发
 * @param message 提醒正文,直接追加到工具结果或作为系统消息塞回
 * @param refs 建议阅读的记忆名/规则名列表,渲染进提醒文本
 */
@Serializable
data class ReminderRule(
    val id: String,
    val enabled: Boolean = true,
    val layer: ReminderLayer = ReminderLayer.TOOL,
    val tools: List<String> = emptyList(),
    val conditions: List<ReminderCondition> = emptyList(),
    val match: MatchMode = MatchMode.ALL,
    val message: String,
    val refs: List<String> = emptyList()
) {
    /** 判定本条规则是否作用于指定工具名(空列表=全部生效) */
    fun appliesTo(toolName: String): Boolean =
        enabled && (tools.isEmpty() || toolName in tools)
}

@Serializable
enum class ReminderLayer {
    /** 工具执行后立即检查,命中就把提醒追加到结果尾部 */
    @SerialName("tool")
    TOOL,

    /** 轮次即将结束(模型给出不带工具调用的最终回复)时检查,触发且未解除就塞一条消息回去让模型继续 */
    @SerialName("turn_end")
    TURN_END
}

@Serializable
enum class MatchMode {
    /** 条件全部命中才触发 */
    @SerialName("all")
    ALL,

    /** 任一条件命中即触发 */
    @SerialName("any")
    ANY
}

/**
 * 提醒触发条件(多态);解析失败或未知类型的条目会被跳过并记日志,不影响整份文件的其它规则。
 *
 * tool 层可用的条件类型:
 * - [ArgsRegex]: 参数 JSON 文本正则匹配
 * - [ResultRegex]: 结果文本正则匹配
 * - [IsError]: 结果是否为错误状态
 *
 * turn_end 层额外可用:
 * - [TriggerCondition]: 本轮有任一工具调用命中下面列出的一组条件即触发
 * - [ResolveCondition]: 触发之后有工具调用命中这组条件即解除
 */
@Serializable
sealed class ReminderCondition {
    /**
     * 参数 JSON 文本正则匹配(忽略大小写)。
     * 匹配前会把参数 Map 序列化成 JSON 字符串(紧凑格式、无空格)。
     */
    @Serializable
    @SerialName("args_regex")
    data class ArgsRegex(val pattern: String) : ReminderCondition()

    /**
     * 结果文本正则匹配(忽略大小写)。
     * 注意:为避免大输出卡住,匹配前会先把结果截到前后各 8KB。
     */
    @Serializable
    @SerialName("result_regex")
    data class ResultRegex(val pattern: String) : ReminderCondition()

    /**
     * 结果是否为错误状态(ToolResult.Error 或 is_error=true)。
     */
    @Serializable
    @SerialName("is_error")
    data class IsError(val value: Boolean) : ReminderCondition()

    /**
     * turn_end 层专用:本轮任一工具调用命中下列条件即触发。
     * 内层条件列表只能包含 ArgsRegex/ResultRegex/IsError,不能嵌套 TriggerCondition/ResolveCondition。
     */
    @Serializable
    @SerialName("trigger")
    data class TriggerCondition(
        val conditions: List<ReminderCondition> = emptyList(),
        val match: MatchMode = MatchMode.ALL
    ) : ReminderCondition()

    /**
     * turn_end 层专用:触发之后有工具调用命中下列条件即解除。
     * 内层条件列表只能包含 ArgsRegex/ResultRegex/IsError,不能嵌套 TriggerCondition/ResolveCondition。
     */
    @Serializable
    @SerialName("resolve")
    data class ResolveCondition(
        val conditions: List<ReminderCondition> = emptyList(),
        val match: MatchMode = MatchMode.ALL
    ) : ReminderCondition()
}

/** 序列化配置:容忍未知字段(向前兼容)、优雅降级不解析失败 */
internal val ReminderJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    prettyPrint = true
    encodeDefaults = true
}
