package com.aharou.feature.agent.domain.provider

import com.aharou.feature.agent.data.remote.gemini.InteractionContent
import com.aharou.feature.agent.data.remote.gemini.InteractionStep
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ToolCall
import com.aharou.feature.agent.domain.tool.modelToolResultText
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 工具定义 → Interactions 的扁平 function 声明；无工具时返回 null（不发 `tools` 字段）。
 *
 * 与 generateContent 不同：不再套 `[{functionDeclarations: [...]}]` 那一层，每个工具就是
 * `tools` 数组里一个带 `type: "function"` 的对象。
 */
internal fun buildInteractionsTools(tools: List<AgentTool>): List<Map<String, Any>>? =
    tools.takeIf { it.isNotEmpty() }?.map { tool ->
        mapOf(
            "type" to "function",
            "name" to tool.name,
            "description" to tool.description,
            "parameters" to tool.toJsonSchema()
        )
    }

/**
 * 会话历史 → Interactions 的 `input` step 序列（无状态模式，请求带 `store=false`）。
 *
 * 与 generateContent 的 `contents`（user/model 两种 role + parts）不同：这里是一条扁平的
 * 时间线，模型输出、思考、工具调用、工具结果各自是独立 step。
 *
 * 配对约束与 Responses 一致且同样必须在客户端保证：`function_result` 必须能找到同 `call_id`
 * 的 `function_call`，声明过的 `function_call` 也必须有结果。这里按调用逐个吸附其结果，顺带
 * 处理两类历史脏数据：
 * - 结果乱序落位（如 askUserQuestion 阻塞期间其他工具结果插队）→ 吸附回调用之后；
 * - 孤立结果（前驱调用已被上下文压缩裁掉）→ 丢弃；
 * - 调用无结果（如用户拒绝执行）→ 连调用一起裁掉。
 *
 * **同一轮的多个 `function_call` 先连续写完，再集中写 `function_result`**，与官方无状态
 * 示例（先 append 整批模型 step，再 append 结果）一致。
 */
internal fun buildInteractionsInput(messages: List<AgentMessage>): List<Any> {
    val steps = mutableListOf<Any>()
    val consumed = BooleanArray(messages.size)
    for (i in messages.indices) {
        if (consumed[i]) continue
        when (val message = messages[i]) {
            is AgentMessage.UserMessage -> steps.add(
                mapOf(
                    "type" to InteractionStep.USER_INPUT,
                    "content" to message.toInteractionContent()
                )
            )

            is AgentMessage.AssistantMessage -> {
                val paired = message.toolCalls.mapNotNull { call ->
                    val resultIndex = (i + 1 until messages.size).firstOrNull { j ->
                        !consumed[j] && (messages[j] as? AgentMessage.ToolResultMessage)?.id == call.id
                    } ?: return@mapNotNull null
                    consumed[resultIndex] = true
                    call to (messages[resultIndex] as AgentMessage.ToolResultMessage)
                }
                steps.addAll(message.toModelSteps(paired.map { it.first }))
                for ((call, result) in paired) {
                    steps.add(
                        mapOf(
                            "type" to InteractionStep.FUNCTION_RESULT,
                            "call_id" to call.id,
                            "name" to call.name,
                            "result" to result.toInteractionResult()
                        )
                    )
                }
            }

            is AgentMessage.ToolResultMessage -> consumed[i] = true
        }
    }
    return steps
}

/**
 * 一轮 assistant 输出 → 模型 step 序列。
 *
 * 官方要求无状态模式下把模型产出的 step **原样回传**（`thought` 的 signature 与 function_call
 * 都不可重建），故优先用落库的快照。但原样回放的前提是本轮调用全部配到了结果：一旦有调用被
 * 裁掉（如用户拒绝执行），快照里的 signature 就对不上实际发出的调用序列，这时退回按正文重建。
 */
private fun AgentMessage.AssistantMessage.toModelSteps(keptCalls: List<ToolCall>): List<Any> {
    // 有图片的轮不走快照：快照会携大 base64（4K 图约 10MB）撑爆数据库行，且快照不含图片回放
    // 就断了多轮编辑。宁可重建（此轮几乎不涉及工具，thought signature 丢失影响可控），
    // 把图片 block 并入 model_output 完整回传。
    decodeInteractionSteps(thinkingBlocksJson)
        ?.takeIf { keptCalls.size == toolCalls.size && images.isEmpty() }
        ?.let { return it }

    val steps = mutableListOf<Any>()
    val contentBlocks = buildList {
        if (content.isNotBlank()) add(textContent(content))
        images.forEach { add(it.toImageContent()) }
    }
    if (contentBlocks.isNotEmpty()) {
        steps.add(
            mapOf(
                "type" to InteractionStep.MODEL_OUTPUT,
                "content" to contentBlocks
            )
        )
    }
    keptCalls.forEach { call ->
        steps.add(
            mapOf(
                "type" to InteractionStep.FUNCTION_CALL,
                "id" to call.id,
                "name" to call.name,
                "arguments" to call.argumentsAsJson()
            )
        )
    }
    return steps
}

/** 用户消息内容：正文 + 图片各成一个 content block。 */
private fun AgentMessage.UserMessage.toInteractionContent(): List<Map<String, Any>> {
    val blocks = mutableListOf<Map<String, Any>>()
    if (content.isNotBlank()) blocks.add(textContent(content))
    images.forEach { blocks.add(it.toImageContent()) }
    // 空数组会被服务端拒，退化成一个（可能为空的）文本块。
    return blocks.ifEmpty { listOf(textContent(content)) }
}

/**
 * 工具结果：以 `{"result": 文本}` 对象回传。
 *
 * 不回传工具产出的图片：生成图已展示给用户并落盘，再进上下文只会膨胀 token，
 * 且部分中转网关把 interactions 转 generateContent 时无法处理数组型 result
 * （会把 content 数组直接塞给 function_response.response 报 `cannot start list`）。
 * 官方 Interactions 的 result 接受对象/字符串/Content 数组，对象最稳。
 */
private fun AgentMessage.ToolResultMessage.toInteractionResult(): Any {
    // 文件类工具喂模型用精简投影文本，UI/持久化仍走完整 result。
    val modelText = modelResult
        ?: modelToolResultText(toolName, result)
        ?: result
    return if (modelText.isBlank()) {
        listOf(textContent(modelText))
    } else {
        mapOf("result" to modelText)
    }
}

/**
 * 模型产出 step 的原样快照（JSON 数组文本），存进 [AgentMessage.AssistantMessage.thinkingBlocksJson]，
 * 下一轮原样回传。
 *
 * 只在存在不可重建内容（`thought` 的 signature / `function_call`）时才生成，纯文本轮返回 null
 * 让历史走常规重建，避开正文双写。`user_input` / `function_result` 是我们自己发过去的输入回显，
 * 会被这里过滤掉——留着下一轮就重复了。
 */
internal fun snapshotInteractionSteps(steps: List<JsonObject>): String? {
    val generated = steps.filter { it.stepType() !in ECHOED_STEP_TYPES }
    val needsSnapshot = generated.any {
        it.stepType() == InteractionStep.THOUGHT || it.stepType() == InteractionStep.FUNCTION_CALL
    }
    if (!needsSnapshot) return null
    return JsonArray().apply { generated.forEach { add(it) } }.toString()
}

internal fun snapshotInteractionSteps(steps: JsonArray?): String? {
    if (steps == null) return null
    return snapshotInteractionSteps(steps.mapNotNull { it.takeIf { el -> el.isJsonObject }?.asJsonObject })
}

/** 快照 → step 列表；为空或损坏时返回 null，由调用方回退重建逻辑。 */
internal fun decodeInteractionSteps(json: String): List<JsonObject>? {
    if (json.isBlank()) return null
    return runCatching {
        JsonParser.parseString(json).asJsonArray
            .mapNotNull { it.takeIf { el -> el.isJsonObject }?.asJsonObject }
            .filter { it.stepType() !in ECHOED_STEP_TYPES }
            .takeIf { it.isNotEmpty() }
    }.getOrNull()
}

/**
 * 工具入参 → Gson 树。直接把 kotlinx 的 JsonObject 交给 Gson 会按反射序列化它的内部字段
 * （得到 `{"body":...,"isString":true}` 这种垃圾），必须先转成文本再让 Gson 解析回来。
 */
private fun ToolCall.argumentsAsJson(): JsonElement =
    runCatching {
        JsonParser.parseString(kotlinx.serialization.json.JsonObject(arguments).toString())
    }.getOrElse { JsonObject() }

/** 会话历史里由我们自己拼出、不该出现在模型产出快照里的 step 类型。 */
private val ECHOED_STEP_TYPES = setOf(InteractionStep.USER_INPUT, InteractionStep.FUNCTION_RESULT)

private fun textContent(text: String): Map<String, Any> =
    mapOf("type" to InteractionContent.TEXT, "text" to text)

private fun AgentImage.toImageContent(): Map<String, Any> = mapOf(
    "type" to InteractionContent.IMAGE,
    "mime_type" to mimeType,
    "data" to base64Data
)

private fun JsonObject.stepType(): String? =
    get("type")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
