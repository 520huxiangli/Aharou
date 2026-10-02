package com.aharou.feature.agent.data.remote.openai

/**
 * Responses API 的 function 工具定义：字段直接铺在 item 上，没有 Chat Completions 的
 * `function` 嵌套层。发嵌套结构会被服务端以「tools[0]: missing field `name`」拒绝。
 */
data class ResponsesToolDefinition(
    val type: String = "function",
    val name: String,
    val description: String,
    val parameters: Map<String, Any>,
    /**
     * 官方 schema 里 `strict` 不带 optional 标记，固定发 false：严格模式要求入参 schema
     * 满足结构化输出子集（全字段 required + additionalProperties:false），工具不保证满足。
     */
    val strict: Boolean = false
)

/**
 * Responses 流式 SSE 的语义事件类型。
 * 与 Chat Completions 的 delta 帧不同，流以 [COMPLETED] / [INCOMPLETE] / [FAILED] 之一结束，
 * **不发 `data: [DONE]`**，故结束判定只能看终止事件。
 */
object ResponsesEvent {
    const val OUTPUT_TEXT_DELTA = "response.output_text.delta"
    /** 模型拒答：拒答说明走单独事件与单独 part，不会出现在 output_text 里。 */
    const val REFUSAL_DELTA = "response.refusal.delta"
    const val REASONING_TEXT_DELTA = "response.reasoning_text.delta"
    /** 官方推理模型（o 系列 / gpt-5 系列）的思考摘要走这个事件，而非 reasoning_text。 */
    const val REASONING_SUMMARY_TEXT_DELTA = "response.reasoning_summary_text.delta"
    const val OUTPUT_ITEM_ADDED = "response.output_item.added"
    const val OUTPUT_ITEM_DONE = "response.output_item.done"
    const val FUNCTION_CALL_ARGS_DELTA = "response.function_call_arguments.delta"
    const val FUNCTION_CALL_ARGS_DONE = "response.function_call_arguments.done"
    const val COMPLETED = "response.completed"
    const val INCOMPLETE = "response.incomplete"
    const val FAILED = "response.failed"
    /** 传输层错误事件：与三个终止事件并列，自身就带 code / message。 */
    const val ERROR = "error"
    /** 同上，官方 SDK 对两种命名都做了处理。 */
    const val RESPONSE_ERROR = "response.error"
}

/** Responses 输入/输出 item 的 `type` 取值。 */
object ResponsesItem {
    const val MESSAGE = "message"
    const val FUNCTION_CALL = "function_call"
    const val FUNCTION_CALL_OUTPUT = "function_call_output"
    const val REASONING = "reasoning"
    /** 模型经服务端 image_generation 工具产出的图片项，`result` 为 base64。 */
    const val IMAGE_GENERATION_CALL = "image_generation_call"
}

/** Responses content part 的 `type` 取值。 */
object ResponsesPart {
    const val INPUT_TEXT = "input_text"
    const val OUTPUT_TEXT = "output_text"
    const val INPUT_IMAGE = "input_image"
    const val REASONING_TEXT = "reasoning_text"
    /** reasoning item 的 `summary` 数组元素类型。 */
    const val SUMMARY_TEXT = "summary_text"
    /** 模型拒答时 message item 里的 part，文本在 `refusal` 字段而非 `text`。 */
    const val REFUSAL = "refusal"
}
