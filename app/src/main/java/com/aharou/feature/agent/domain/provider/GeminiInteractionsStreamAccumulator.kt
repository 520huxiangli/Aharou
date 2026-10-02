package com.aharou.feature.agent.domain.provider

import com.aharou.feature.agent.data.remote.gemini.InteractionContent
import com.aharou.feature.agent.data.remote.gemini.InteractionDelta
import com.aharou.feature.agent.data.remote.gemini.InteractionEvent
import com.aharou.feature.agent.data.remote.gemini.InteractionStep
import com.aharou.feature.agent.data.remote.gemini.TERMINAL_STATUSES
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.domain.tool.ToolCall
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Interactions `steps` 数组的解析结果。 */
internal data class InteractionsOutput(
    val text: String = "",
    val reasoning: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    /** 模型产出的图片（Nano Banana 图像模型），每个元素带 base64 数据；非图像模型恒为空。 */
    val images: List<AgentImage> = emptyList(),
    /** 模型产出 step 的原样快照，下一轮无状态回放时原样回传；无需快照时为 null。 */
    val stepsSnapshotJson: String? = null
)

/** Interactions 的 token 用量。 */
internal data class InteractionsUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cachedInputTokens: Int = 0
)

/**
 * usage 字段相比 generateContent 全部改名，且 `total_output_tokens` **不含**思考 token
 * （官方示例里 `total_tokens = input + output + thought`）。思考按输出价计费，故两者相加
 * 才是真实输出量。
 */
internal fun parseInteractionsUsage(usage: JsonObject?): InteractionsUsage {
    if (usage == null) return InteractionsUsage()
    return InteractionsUsage(
        inputTokens = usage.int("total_input_tokens"),
        outputTokens = usage.int("total_output_tokens") + usage.int("total_thought_tokens"),
        cachedInputTokens = usage.int("total_cached_tokens")
    )
}

/**
 * 解析 Interactions 的 `steps` 数组：非流式响应与流式终止事件的兜底共用。
 *
 * 与 generateContent 的关键差别：正文、思考、工具调用不再是同一个 `parts` 数组里的兄弟元素，
 * 而是时间线上各自独立的 step —— 工具调用是 `function_call` step（自带 `id` / `name` /
 * `arguments` 对象），按旧写法去 content 里找永远解析不到。
 */
internal fun parseInteractionSteps(steps: JsonArray?): InteractionsOutput {
    if (steps == null) return InteractionsOutput()
    val text = StringBuilder()
    val reasoning = StringBuilder()
    val toolCalls = mutableListOf<ToolCall>()
    val images = mutableListOf<AgentImage>()
    steps.forEach { element ->
        // 单个 step 解析失败不应废掉整个响应
        runCatching {
            val step = element.asJsonObject
            when (step.str("type")) {
                InteractionStep.MODEL_OUTPUT -> step.arr("content")?.forEach { block ->
                    val content = block.asJsonObject
                    when (content.str("type")) {
                        InteractionContent.TEXT -> text.append(content.str("text").orEmpty())
                        InteractionContent.IMAGE -> content.toAgentImage()?.let { images.add(it) }
                    }
                }

                InteractionStep.THOUGHT -> step.arr("summary")?.forEach { block ->
                    val content = block.asJsonObject
                    if (content.str("type") == InteractionContent.TEXT) {
                        reasoning.append(content.str("text").orEmpty())
                    }
                }

                InteractionStep.FUNCTION_CALL -> {
                    val name = step.str("name").orEmpty()
                    toolCalls.add(
                        ToolCall(
                            // 并行调用靠 id 配对；缺失才退回函数名（同名工具调两次会撞 id）。
                            id = step.str("id")?.takeIf { it.isNotBlank() } ?: name,
                            name = name,
                            arguments = parseToolArguments(step.obj("arguments")?.toString().orEmpty())
                        )
                    )
                }
            }
        }
    }
    return InteractionsOutput(
        text = text.toString(),
        reasoning = reasoning.toString(),
        toolCalls = toolCalls,
        images = images,
        stepsSnapshotJson = snapshotInteractionSteps(steps)
    )
}

/**
 * Interactions 的 `status` → 统一 stopReason。
 *
 * `incomplete`（撞 `max_output_tokens`）必须映射成 `length`：与 Chat Completions 的取值对齐后
 * [AIResponse.isTruncated] 才成立、Agent 循环才会自动续写，否则表现为回答被无声截断。
 * 反过来不能把 `incomplete` 直接收进 [AIResponse.TRUNCATION_STOP_REASONS]——Responses API 的
 * 同名状态还涵盖 content_filter 等非截断原因，共用一个词会把那些也误判成截断。
 *
 * 其余状态原样透出：`requires_action` 表示有工具待执行，`failed` / `budget_exceeded` 命中
 * [AIResponse.ABORT_STOP_REASONS]，由上层向用户解释而非静默完成。
 */
internal fun interactionStopReason(status: String?): String? =
    if (status == "incomplete") "length" else status

/** 流式事件产生的增量文本，供上层推送给 UI。 */
internal sealed class InteractionsDelta {
    data class Text(val text: String) : InteractionsDelta()
    data class Reasoning(val text: String) : InteractionsDelta()

    /** 刚开始产出某次 function_call、工具名已知（参数还在流式传输中），供 UI 提前说清「在调什么」。 */
    data class ToolCallDeclared(val name: String) : InteractionsDelta()
}

/**
 * Interactions 流式事件累积器。
 *
 * 事件判别字段是 **`event_type`**（迁移指南的示例写 `type`，与参考文档矛盾，这里两个都认）。
 * step 事件按 `index` 归组：`step.start` 给出 step 类型与 function_call 的 id/name，
 * `step.delta` 逐片送来正文 / 思考摘要 / 思考签名 / 工具入参，`step.stop` 收尾。
 *
 * 流**不发 `[DONE]`**，且**有工具待执行时服务端停在 `requires_action`、不会再发 `completed`**，
 * 所以结束判定看 interaction 的 status 是否已到终态（[TERMINAL_STATUSES]）；只等 completed
 * 会一路读到流尾被误判成断流。
 */
internal class GeminiInteractionsStreamAccumulator {
    var receivedContent: Boolean = false
        private set

    private class StepAcc {
        var type: String? = null
        var id = ""
        var name = ""
        var signature: String? = null
        val text = StringBuilder()
        val summary = StringBuilder()

        /** `arguments_delta` 逐字累积的入参片段。 */
        val argsDelta = StringBuilder()

        /** `step.start` 直接给出的完整入参（部分实现不走 delta）。 */
        var argsComplete: String? = null

        /** `step.start` 的原始 step 对象：服务端工具等我们不解析的 step 原样留着回放。 */
        var raw: JsonObject? = null

        /** step.start 缺失时按已收到的增量反推类型。 */
        fun effectiveType(): String? = type ?: when {
            text.isNotEmpty() -> InteractionStep.MODEL_OUTPUT
            summary.isNotEmpty() || signature != null -> InteractionStep.THOUGHT
            name.isNotEmpty() -> InteractionStep.FUNCTION_CALL
            else -> null
        }

        /** 优先用 delta 累积值；它缺失或拼坏了才退回 step.start 给的完整入参。 */
        fun arguments(): kotlinx.serialization.json.JsonObject =
            parseArgsOrNull(argsDelta.toString())
                ?: parseArgsOrNull(argsComplete.orEmpty())
                ?: kotlinx.serialization.json.JsonObject(emptyMap())
    }

    private val steps = LinkedHashMap<Int, StepAcc>()
    private val budget = StreamBudget()
    private var status: String? = null
    private var statusDetail: String? = null
    private var usage = InteractionsUsage()

    /** 终止事件里兜底解析出的产出（流中 delta 缺失时的补齐来源）。 */
    private var finalOutput: InteractionsOutput? = null

    /** 是否已读到终态。未到终态就断流说明流被截断，调用方应按异常处理。 */
    var terminated = false
        private set

    /** 处理一个 SSE data 行解析出的事件对象，返回本次事件带来的增量文本（无则 null）。 */
    fun accept(event: JsonObject): InteractionsDelta? {
        // usage 可能挂在任意事件的 metadata 上，先无条件吸一次。
        event.obj("metadata")?.obj("total_usage")?.let { usage = parseInteractionsUsage(it) }

        val eventType = event.str("event_type") ?: event.str("type")
        when {
            eventType == InteractionEvent.STEP_START -> {
                val step = event.obj("step") ?: return null
                val acc = steps.getOrPut(event.int("index")) { StepAcc() }
                acc.type = step.str("type")
                acc.raw = step
                step.str("id")?.takeIf { it.isNotBlank() }?.let { acc.id = it }
                // 函数调用 step 的工具名先于参数到达：让 UI 提前把「正在思考」换成具体场景。
                // 名字只记不返回——step.start 还可能带完整 arguments / 正文兜底，
                // 在这里提前 return 会把它们丢掉，本分支跑完再统一返回。
                var declaredName: String? = null
                step.str("name")?.takeIf { it.isNotBlank() }?.let { name ->
                    if (acc.name.isBlank()) declaredName = name
                    acc.name = name
                }
                step.str("signature")?.let { acc.signature = it }
                step.obj("arguments")?.let { budget.add(it.toString()); acc.argsComplete = it.toString() }
                // 兜底：部分实现直接在 step.start 的 content 里带完整正文（随后没有 delta），
                // 此时文本增量拿不到正文。仅在尚未累积到时提取，避免与 delta 双写。
                if (acc.text.isEmpty()) {
                    step.arr("content")?.forEach { block ->
                        val content = block.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                        if (content.str("type") == InteractionContent.TEXT) {
                            val text = content.str("text").orEmpty()
                            budget.add(text)
                            acc.text.append(text)
                            if (text.isNotEmpty()) receivedContent = true
                        }
                    }
                }
                step.arr("content")?.forEach { block ->
                    val content = block.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                    if (content.str("type") == InteractionContent.IMAGE && content.toAgentImage() != null) {
                        receivedContent = true
                    }
                }
                declaredName?.let { return InteractionsDelta.ToolCallDeclared(it) }
            }

            eventType == InteractionEvent.STEP_DELTA -> {
                val delta = event.obj("delta") ?: return null
                val acc = steps.getOrPut(event.int("index")) { StepAcc() }
                when (delta.str("type")) {
                    InteractionDelta.TEXT -> {
                        val text = delta.str("text").orEmpty()
                        if (text.isEmpty()) return null
                        budget.add(text)
                        acc.text.append(text)
                        return InteractionsDelta.Text(text)
                    }

                    // 思考摘要文本在 `content.text`（Content 对象）；部分实现直接铺 `text`。
                    InteractionDelta.THOUGHT_SUMMARY, InteractionDelta.THOUGHT -> {
                        val text = delta.obj("content")?.str("text") ?: delta.str("text").orEmpty()
                        if (text.isEmpty()) return null
                        budget.add(text)
                        acc.summary.append(text)
                        return InteractionsDelta.Reasoning(text)
                    }

                    InteractionDelta.THOUGHT_SIGNATURE ->
                        delta.str("signature")?.let { acc.signature = it }

                    InteractionDelta.ARGUMENTS, InteractionDelta.ARGUMENTS_LEGACY -> {
                        val args = delta.str("arguments") ?: delta.str("partial_arguments").orEmpty()
                        budget.add(args)
                        acc.argsDelta.append(args)
                    }
                }
            }

            eventType == InteractionEvent.STEP_STOP ->
                event.obj("usage")?.let { usage = parseInteractionsUsage(it) }

            // 传输层 / 平台错误：先置终止位，避免调用方继续读流尾误报断流。
            eventType == InteractionEvent.ERROR -> {
                terminated = true
                val error = event.obj("error")
                throw StreamApiException(
                    code = error?.str("code"),
                    message = error?.str("message") ?: "Interactions 流返回 error 事件"
                )
            }

            eventType != null && eventType.startsWith(InteractionEvent.LIFECYCLE_PREFIX) ->
                absorbLifecycle(eventType, event)
        }
        return null
    }

    /**
     * 生命周期事件：status 可能在 `interaction.status`（created / completed）或事件顶层
     * （`interaction.status_update`）；`interaction.requires_action` 这种把状态写在事件名里的
     * 也要认，否则工具轮永远等不到终态。
     *
     * 事件名只作兜底——`interaction.completed` 的载荷里可能写着 `incomplete` / `failed`
     * （完成了但结果不完整），拿事件名去覆盖会把截断与失败都抹成正常完成。
     */
    private fun absorbLifecycle(eventType: String, event: JsonObject) {
        val interaction = event.obj("interaction")
        val payloadStatus = interaction?.str("status") ?: event.str("status")
        val nameStatus = eventType.removePrefix(InteractionEvent.LIFECYCLE_PREFIX)
            .takeIf { it in TERMINAL_STATUSES }
        (payloadStatus ?: nameStatus)?.let { status = it }
        interaction?.obj("usage")?.let { usage = parseInteractionsUsage(it) }
        interaction?.arr("steps")?.takeIf { it.size() > 0 }?.let { finalOutput = parseInteractionSteps(it) }
        // 平台错误详情：status=failed 时正文通常为空，只有这里有可展示的理由。
        interaction?.arr("errors")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
            ?.str("message")?.let { statusDetail = it }
        if (status in TERMINAL_STATUSES) terminated = true
    }

    fun toResponse(): AIResponse {
        val streamed = buildStreamedOutput()
        val toolCalls = streamed.toolCalls.ifEmpty { finalOutput?.toolCalls.orEmpty() }
        // 流式下图片整块到达（含 step.start 的完整载荷），不产生流式增量；终止事件里还有兜底解析。
        val images = streamed.images.ifEmpty { finalOutput?.images.orEmpty() }
        return AIResponse(
            content = streamed.text.ifEmpty { finalOutput?.text.orEmpty() },
            toolCalls = toolCalls,
            stopReason = interactionStopReason(status),
            stopDetail = statusDetail,
            reasoning = streamed.reasoning.ifEmpty { finalOutput?.reasoning.orEmpty() }.takeIf { it.isNotEmpty() },
            thinkingBlocksJson = streamed.stepsSnapshotJson ?: finalOutput?.stepsSnapshotJson,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            cachedInputTokens = usage.cachedInputTokens,
            images = images
        )
    }

    /**
     * 把按 index 累积的 step 还原成产出 + 可回放的 step 快照。
     * 快照必须包含 `thought` 的 signature 与完整的 `function_call`：无状态模式下这些原样回传
     * 才能接上推理上下文（[snapshotInteractionSteps] 会剔掉不需要快照的纯文本轮）。
     */
    private fun buildStreamedOutput(): InteractionsOutput {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<ToolCall>()
        val images = mutableListOf<AgentImage>()
        val rebuilt = mutableListOf<JsonObject>()
        steps.toSortedMap().values.forEach { acc ->
            when (acc.effectiveType()) {
                InteractionStep.MODEL_OUTPUT -> {
                    text.append(acc.text)
                    // step.start 的完整载荷里可能直接带 image content（图片不出流式增量，整块到达）。
                    acc.raw?.arr("content")?.forEach { block ->
                        val content = block.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                        if (content.str("type") == InteractionContent.IMAGE) content.toAgentImage()?.let { images.add(it) }
                    }
                    rebuilt.add(modelOutputStep(acc.text.toString()))
                }

                InteractionStep.THOUGHT -> {
                    reasoning.append(acc.summary)
                    rebuilt.add(thoughtStep(acc.signature, acc.summary.toString()))
                }

                InteractionStep.FUNCTION_CALL -> {
                    if (acc.name.isEmpty()) return@forEach
                    val callId = acc.id.ifEmpty { acc.name }
                    val args = acc.arguments()
                    toolCalls.add(ToolCall(id = callId, name = acc.name, arguments = args))
                    rebuilt.add(functionCallStep(callId, acc.name, args))
                }

                // 服务端工具（google_search_call 等）我们不解析，但带着 signature，原样留着回放。
                else -> acc.raw?.let { rebuilt.add(it) }
            }
        }
        return InteractionsOutput(
            text = text.toString(),
            reasoning = reasoning.toString(),
            toolCalls = toolCalls,
            images = images,
            stepsSnapshotJson = snapshotInteractionSteps(rebuilt)
        )
    }

    private fun textBlock(text: String) = JsonObject().apply {
        addProperty("type", InteractionContent.TEXT)
        addProperty("text", text)
    }

    private fun modelOutputStep(text: String) = JsonObject().apply {
        addProperty("type", InteractionStep.MODEL_OUTPUT)
        add("content", JsonArray().apply { add(textBlock(text)) })
    }

    private fun thoughtStep(signature: String?, summary: String) = JsonObject().apply {
        addProperty("type", InteractionStep.THOUGHT)
        signature?.let { addProperty("signature", it) }
        if (summary.isNotEmpty()) add("summary", JsonArray().apply { add(textBlock(summary)) })
    }

    private fun functionCallStep(
        id: String,
        name: String,
        arguments: kotlinx.serialization.json.JsonObject
    ) = JsonObject().apply {
        addProperty("type", InteractionStep.FUNCTION_CALL)
        addProperty("id", id)
        addProperty("name", name)
        add("arguments", JsonParser.parseString(arguments.toString()))
    }
}

private fun parseArgsOrNull(raw: String): kotlinx.serialization.json.JsonObject? =
    raw.trim().takeIf { it.isNotEmpty() }?.let {
        runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
    }

private fun JsonObject.str(name: String): String? =
    get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

/** Interactions 的 image content block → [AgentImage]。data 缺失（异常响应）时返回 null。 */
private fun JsonObject.toAgentImage(): AgentImage? {
    val data = str("data").orEmpty()
    if (data.isEmpty()) return null
    return AgentImage(
        mimeType = str("mime_type") ?: str("mimeType") ?: "image/png",
        base64Data = data
    )
}

private fun JsonObject.int(name: String): Int =
    get(name)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asInt ?: 0

// 下面两个取代 Gson 自带的 getAsJsonArray / getAsJsonObject：它们直接强转，遇到 null
// （如 thought step 缺失的 `summary`）会抛 ClassCastException。
private fun JsonObject.arr(name: String): JsonArray? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.obj(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject
