package com.aharou.feature.agent.data.remote.gemini

/**
 * Interactions API（`v1beta/interactions`）的 step `type` 取值。
 *
 * 与 generateContent 的 `contents[].parts[]` 不同：一轮对话被拆成时间线上的独立 step，
 * 工具调用、思考、模型输出各占一个 step，无状态回放时整条历史就是 step 数组。
 */
object InteractionStep {
    /** 用户输入，正文在 `content`（Content 数组，也接受纯字符串）。 */
    const val USER_INPUT = "user_input"

    /** 模型输出，正文在 `content`（Content 数组）。 */
    const val MODEL_OUTPUT = "model_output"

    /**
     * 思考 step：`signature` 是后端校验用的签名，`summary` 是可选的思考摘要（Content 数组）。
     * 无状态模式下必须原样回传，否则接不上上一轮的推理上下文。
     */
    const val THOUGHT = "thought"

    /** 模型请求调用函数：`id` / `name` / `arguments`（对象，非字符串）。 */
    const val FUNCTION_CALL = "function_call"

    /** 函数执行结果：`call_id` 对应调用的 `id`，`result` 是 Content 数组 / 对象 / 字符串。 */
    const val FUNCTION_RESULT = "function_result"
}

/** Interactions 的 Content block `type` 取值。 */
object InteractionContent {
    const val TEXT = "text"
    const val IMAGE = "image"
}

/**
 * Interactions 流式 SSE 的事件类型，判别字段是 **`event_type`**（不是 `type`）。
 *
 * 生命周期事件带 `interaction` 对象（含 status / usage），step 事件按 `index` 归组。
 * 流**不发 `[DONE]`**，结束只能看 interaction 的 status 是否已到终态（见 [TERMINAL_STATUSES]）。
 */
object InteractionEvent {
    const val STEP_START = "step.start"
    const val STEP_DELTA = "step.delta"
    const val STEP_STOP = "step.stop"

    /** 传输层 / 平台错误，自身带 `error: {code, message}`。 */
    const val ERROR = "error"

    /** 生命周期事件前缀：created / in_progress / status_update / requires_action / completed 等。 */
    const val LIFECYCLE_PREFIX = "interaction."
}

/** `step.delta` 里 `delta.type` 的取值。 */
object InteractionDelta {
    /** 正文增量，文本在 `text`。 */
    const val TEXT = "text"

    /** 思考摘要增量：文本在 `content.text`（部分实现直接铺 `text`）。 */
    const val THOUGHT_SUMMARY = "thought_summary"

    /** 同上的另一种写法，迁移指南示例用的是这个。 */
    const val THOUGHT = "thought"

    /** 思考签名，值在 `signature`；挂在所属 thought step 上。 */
    const val THOUGHT_SIGNATURE = "thought_signature"

    /** 工具入参逐字增量，片段在 `arguments`（迁移指南写作 `partial_arguments`）。 */
    const val ARGUMENTS = "arguments_delta"

    /** 同上的另一种写法。 */
    const val ARGUMENTS_LEGACY = "arguments"
}

/**
 * interaction 的终态。流式下读到任一终态即可停止读流：
 * 有工具待执行时服务端停在 `requires_action`，**不会**再发 `completed`，
 * 只等 `completed` 会一路读到流尾、被误判成断流。
 */
val TERMINAL_STATUSES = setOf(
    "completed",
    "requires_action",
    "incomplete",
    "failed",
    "cancelled",
    "budget_exceeded"
)
