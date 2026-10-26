package com.aharou.feature.agent.domain.middleware

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 声明式请求中间件配置：用 JSON 描述「请求发出前怎么改写」，与具体 provider 适配器解耦。
 */
@Serializable
data class MiddlewareConfig(
    /** 对外模型名 → 实际发给服务端的模型名。回复侧仍按 [MiddlewareContext.requestedModel] 展示。 */
    val modelMap: Map<String, String> = emptyMap(),
    val paramOverrides: List<ParamOverride> = emptyList(),
    /** 追加到系统提示末尾的片段；null/空白表示不注入。 */
    val systemPrompt: String? = null,
    /**
     * 系统提示的生效范围：可命中 providerId、providerType、请求模型名或映射后的模型名，任一相等即生效。
     * 为空 = 对所有请求生效。
     */
    val systemPromptTargets: List<String> = emptyList(),
    val enabled: Boolean = true
)

@Serializable
data class ParamOverride(
    /** 点号路径，如 `temperature`、`reasoning.effort`。 */
    val path: String,
    val op: ParamOp = ParamOp.SET,
    /** SET 必填；REMOVE 忽略。 */
    val value: JsonElement? = null,
    /** 单条规则的模型条件，null = 不限。 */
    val whenModel: String? = null,
    /** 单条规则的提供商条件，null = 不限。 */
    val whenProvider: String? = null
)

@Serializable
enum class ParamOp {
    SET,
    REMOVE
}

/**
 * 一次请求在中间件视角下的上下文。
 *
 * [requestedModel] 是用户在 UI 上选的名字，[actualModel] 是 `modelMap` 解析后真正发出去的
 * 名字（未配置映射时两者相同）。两者分开保存，回复渲染与会话记录才能继续显示原名。
 */
data class MiddlewareContext(
    val providerId: String,
    val providerType: String,
    val requestedModel: String,
    val actualModel: String = requestedModel
)

/**
 * 求值结果。
 *
 * 除了改写后的请求体，还带上三类规则的归属：真正生效的 [applied]、被安全黑名单拦下的
 * [rejected]、以及配置本身不合法（缺 value、路径非法、非对象节点挡路）而跳过的 [invalid]。
 * 之所以要把它们带出来，是因为中间件默默吞掉规则时，用户无法区分「没配」和「配了不生效」。
 */
data class MiddlewareResult(
    val body: JsonObject,
    val applied: List<ParamOverride> = emptyList(),
    val rejected: List<ParamOverride> = emptyList(),
    val invalid: List<ParamOverride> = emptyList()
)
