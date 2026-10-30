package com.aharou.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 单次 LLM 调用记录：Token 统计页的数据源。
 * 由 workflow 主循环（chat）与上下文压缩链路（compaction）埋点写入，
 * 覆盖渠道、模型、输入/输出/缓存 tokens、首字延迟、耗时与成败。
 */
@Entity(
    tableName = "llm_call_records",
    indices = [
        Index(value = ["createdAt"]),
        Index(name = "index_llm_call_records_provider_model", value = ["providerId", "model"])
    ]
)
data class LlmCallRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String? = null,
    /** 渠道（ai_providers.id），展示时 LEFT JOIN 取渠道名。 */
    val providerId: String? = null,
    /** 实际调用模型（含压缩 fallback 模型）。 */
    val model: String? = null,
    /** 本次调用的思考强度（ReasoningEffort.apiValue，如 "medium"）；未设置/无档位模型为 null。 */
    val reasoningEffort: String? = null,
    /** 调用类型：chat（普通对话）/ compaction（上下文压缩）。历史数据含已废弃的 vision（识图轮）。 */
    val kind: String = "chat",
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    /** 本轮输入中命中服务端缓存的部分（OpenAI cached_tokens / Anthropic cache_read_input_tokens / Gemini cachedContentTokenCount）。 */
    val cachedInputTokens: Int = 0,
    /** 本轮写入服务端缓存的 token 数（Anthropic cache_creation_input_tokens），按高于输入的单价计费。 */
    val cacheCreationTokens: Int = 0,
    /** 首字延迟（毫秒）：流式调用首个文本块到达 - 请求发出；非流式或失败为 null。 */
    val ttfbMillis: Int? = null,
    /** 总耗时（毫秒）：结束 - 请求发出。 */
    val durationMillis: Int? = null,
    /** 调用结果：success / error。 */
    val status: String = "success",
    val errorMessage: String? = null,
    /** 模型结束原因：end_turn / tool_use / max_tokens / stop / length。 */
    val stopReason: String? = null,
    /** 本次交互底层发生网络重试的次数（0 表示首试成功或非重试类失败）。 */
    val retryCount: Int = 0,
    /** 请求发出时间（epoch 毫秒）。 */
    val createdAt: Long
)
