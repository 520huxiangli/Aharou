package com.aharou.feature.agent.domain.provider

import com.aharou.feature.agent.data.remote.gemini.InteractionStep
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ToolCall
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolResult
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Interactions 请求体构造（[buildInteractionsTools] / [buildInteractionsInput]）。
 * 覆盖点：工具声明不再套 `functionDeclarations`、历史是扁平 step 时间线、
 * 模型产出 step 原样回放（thought 的 signature 不可重建）、以及工具调用/结果的配对清理。
 */
class GeminiInteractionsPayloadTest {

    private class FakeTool(
        override val name: String,
        override val description: String = "desc",
        override val parameters: Map<String, ToolParameter> = emptyMap()
    ) : AgentTool() {
        override suspend fun execute(args: Map<String, JsonElement>): ToolResult =
            ToolResult.Success(JsonPrimitive("ok"))
    }

    private fun call(id: String, name: String, path: String) =
        ToolCall(id = id, name = name, arguments = mapOf("path" to JsonPrimitive(path)))

    /** step 元素可能是 Map（重建）或 Gson JsonObject（快照原样回放），统一成 JsonObject 再断言。 */
    private fun steps(messages: List<AgentMessage>): List<JsonObject> =
        buildInteractionsInput(messages).map { Gson().toJsonTree(it).asJsonObject }

    private fun json(raw: String): JsonObject =
        JsonParser.parseString(raw.replace('\'', '"')).asJsonObject

    private fun JsonObject.type(): String = get("type").asString

    @Test
    fun tool_definition_is_flat_without_function_declarations() {
        val defs = buildInteractionsTools(listOf(FakeTool("readFile")))
        assertEquals(1, defs?.size)
        val def = Gson().toJsonTree(defs!!.first()).asJsonObject
        assertEquals("function", def.get("type").asString)
        assertEquals("readFile", def.get("name").asString)
        assertTrue(def.has("description"))
        assertTrue(def.has("parameters"))
        // generateContent 的 [{functionDeclarations: [...]}] 那层在 Interactions 里不存在
        assertFalse(def.has("functionDeclarations"))
    }

    @Test
    fun no_tools_means_no_tools_field() {
        assertNull(buildInteractionsTools(emptyList()))
    }

    @Test
    fun user_message_becomes_user_input_step() {
        val result = steps(listOf(AgentMessage.UserMessage(content = "hi")))
        assertEquals(1, result.size)
        assertEquals(InteractionStep.USER_INPUT, result[0].type())
        val content = result[0].getAsJsonArray("content")
        assertEquals(1, content.size())
        assertEquals("text", content[0].asJsonObject.get("type").asString)
        assertEquals("hi", content[0].asJsonObject.get("text").asString)
    }

    @Test
    fun user_images_become_image_content_blocks() {
        val message = AgentMessage.UserMessage(
            content = "看图",
            images = listOf(AgentImage(mimeType = "image/png", base64Data = "AAA"))
        )
        val content = steps(listOf(message))[0].getAsJsonArray("content")
        assertEquals(2, content.size())
        val image = content[1].asJsonObject
        assertEquals("image", image.get("type").asString)
        // generateContent 用 inline_data 包一层，Interactions 是平铺的 mime_type / data
        assertEquals("image/png", image.get("mime_type").asString)
        assertEquals("AAA", image.get("data").asString)
        assertFalse(image.has("inline_data"))
    }

    @Test
    fun assistant_text_only_turn_is_rebuilt_as_model_output() {
        val result = steps(
            listOf(
                AgentMessage.UserMessage(content = "hi"),
                AgentMessage.AssistantMessage(content = "hello")
            )
        )
        assertEquals(2, result.size)
        assertEquals(InteractionStep.MODEL_OUTPUT, result[1].type())
        assertEquals("hello", result[1].getAsJsonArray("content")[0].asJsonObject.get("text").asString)
    }

    @Test
    fun tool_roundtrip_emits_function_call_then_function_result() {
        val result = steps(
            listOf(
                AgentMessage.UserMessage(content = "读文件"),
                AgentMessage.AssistantMessage(content = "", toolCalls = listOf(call("c1", "readFile", "a.kt"))),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "readFile", result = "内容")
            )
        )
        assertEquals(3, result.size)
        assertEquals(InteractionStep.FUNCTION_CALL, result[1].type())
        assertEquals("c1", result[1].get("id").asString)
        assertEquals("readFile", result[1].get("name").asString)
        assertEquals(InteractionStep.FUNCTION_RESULT, result[2].type())
        // 结果靠 call_id 与调用的 id 配对
        assertEquals("c1", result[2].get("call_id").asString)
        assertEquals("内容", result[2].getAsJsonObject("result").get("result").asString)
    }

    @Test
    fun rebuilt_arguments_are_real_json_not_kotlinx_internals() {
        val result = steps(
            listOf(
                AgentMessage.UserMessage(content = "读文件"),
                AgentMessage.AssistantMessage(content = "", toolCalls = listOf(call("c1", "readFile", "a.kt"))),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "readFile", result = "内容")
            )
        )
        val args = result[1].getAsJsonObject("arguments")
        // 把 kotlinx 的 JsonObject 直接交给 Gson 会反射出 {"body":...,"isString":true}
        assertEquals("a.kt", args.get("path").asString)
        assertFalse(args.has("body"))
    }

    @Test
    fun call_without_result_is_dropped_together_with_the_call() {
        val result = steps(
            listOf(
                AgentMessage.UserMessage(content = "读文件"),
                AgentMessage.AssistantMessage(content = "好", toolCalls = listOf(call("c1", "readFile", "a.kt")))
            )
        )
        // 用户拒绝执行时调用无结果，留着调用会因缺 function_result 被服务端拒
        assertEquals(2, result.size)
        assertEquals(InteractionStep.MODEL_OUTPUT, result[1].type())
    }

    @Test
    fun orphan_tool_result_is_dropped() {
        val result = steps(
            listOf(
                AgentMessage.UserMessage(content = "hi"),
                AgentMessage.ToolResultMessage(id = "gone", toolName = "readFile", result = "内容")
            )
        )
        assertEquals(1, result.size)
        assertEquals(InteractionStep.USER_INPUT, result[0].type())
    }

    @Test
    fun out_of_order_result_is_attached_to_its_call() {
        val result = steps(
            listOf(
                AgentMessage.UserMessage(content = "两个工具"),
                AgentMessage.AssistantMessage(
                    content = "",
                    toolCalls = listOf(call("c1", "ask", "x"), call("c2", "readFile", "a.kt"))
                ),
                // askUserQuestion 阻塞期间 c2 的结果先落位
                AgentMessage.ToolResultMessage(id = "c2", toolName = "readFile", result = "内容"),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "ask", result = "答")
            )
        )
        // 两个调用先连续写完，再集中写两个结果（与官方无状态示例的顺序一致）
        assertEquals(
            listOf(
                InteractionStep.USER_INPUT,
                InteractionStep.FUNCTION_CALL,
                InteractionStep.FUNCTION_CALL,
                InteractionStep.FUNCTION_RESULT,
                InteractionStep.FUNCTION_RESULT
            ),
            result.map { it.type() }
        )
        assertEquals("c1", result[3].get("call_id").asString)
        assertEquals("c2", result[4].get("call_id").asString)
    }

    @Test
    fun snapshot_is_replayed_verbatim_keeping_thought_signature() {
        val snapshot = """
            [{'type':'thought','signature':'sig-1'},
             {'type':'function_call','id':'c1','name':'readFile','arguments':{'path':'a.kt'}}]
        """.trimIndent().replace('\'', '"')
        val result = steps(
            listOf(
                AgentMessage.UserMessage(content = "读文件"),
                AgentMessage.AssistantMessage(
                    content = "",
                    toolCalls = listOf(call("c1", "readFile", "a.kt")),
                    thinkingBlocksJson = snapshot
                ),
                AgentMessage.ToolResultMessage(id = "c1", toolName = "readFile", result = "内容")
            )
        )
        assertEquals(4, result.size)
        // signature 不可重建，必须原样回传，否则接不上上一轮推理
        assertEquals(InteractionStep.THOUGHT, result[1].type())
        assertEquals("sig-1", result[1].get("signature").asString)
        assertEquals(InteractionStep.FUNCTION_CALL, result[2].type())
        assertEquals(InteractionStep.FUNCTION_RESULT, result[3].type())
    }

    @Test
    fun snapshot_is_abandoned_when_a_call_lost_its_result() {
        val snapshot = """
            [{'type':'thought','signature':'sig-1'},
             {'type':'function_call','id':'c1','name':'ask','arguments':{}},
             {'type':'function_call','id':'c2','name':'readFile','arguments':{'path':'a.kt'}}]
        """.trimIndent().replace('\'', '"')
        val result = steps(
            listOf(
                AgentMessage.UserMessage(content = "两个工具"),
                AgentMessage.AssistantMessage(
                    content = "好",
                    toolCalls = listOf(call("c1", "ask", "x"), call("c2", "readFile", "a.kt")),
                    thinkingBlocksJson = snapshot
                ),
                // c1 被用户拒绝，没有结果
                AgentMessage.ToolResultMessage(id = "c2", toolName = "readFile", result = "内容")
            )
        )
        // 快照里的 signature 会对不上实际发出的调用序列，退回按正文重建
        assertEquals(
            listOf(
                InteractionStep.USER_INPUT,
                InteractionStep.MODEL_OUTPUT,
                InteractionStep.FUNCTION_CALL,
                InteractionStep.FUNCTION_RESULT
            ),
            result.map { it.type() }
        )
        assertEquals("c2", result[2].get("id").asString)
    }

    @Test
    fun snapshot_drops_echoed_input_steps() {
        // 服务端可能把我们发过去的 user_input / function_result 一并回显，留着下一轮就重复了
        val steps = listOf(
            json("{'type':'user_input','content':[{'type':'text','text':'hi'}]}"),
            json("{'type':'thought','signature':'sig-1'}"),
            json("{'type':'function_result','call_id':'c1','result':[]}"),
            json("{'type':'model_output','content':[{'type':'text','text':'hello'}]}")
        )
        val snapshot = JsonParser.parseString(snapshotInteractionSteps(steps)!!).asJsonArray
        assertEquals(2, snapshot.size())
        assertEquals(InteractionStep.THOUGHT, snapshot[0].asJsonObject.type())
        assertEquals(InteractionStep.MODEL_OUTPUT, snapshot[1].asJsonObject.type())
    }

    @Test
    fun pure_text_turn_needs_no_snapshot() {
        val steps = listOf(json("{'type':'model_output','content':[{'type':'text','text':'hello'}]}"))
        // 纯文本可由正文重建，落库快照只会导致正文双写
        assertNull(snapshotInteractionSteps(steps))
    }

    @Test
    fun tool_result_is_emitted_as_plain_result_object_without_images() {
        val result = steps(
            listOf(
                AgentMessage.UserMessage(content = "截图"),
                AgentMessage.AssistantMessage(content = "", toolCalls = listOf(call("c1", "shot", "x"))),
                AgentMessage.ToolResultMessage(
                    id = "c1",
                    toolName = "shot",
                    result = "已截图",
                    images = listOf(AgentImage(mimeType = "image/png", base64Data = "BBB"))
                )
            )
        )
        // result 以对象回传（兼容部分中转网关转 generateContent 时对数组型 result 的拒绝）；
        // 工具产出的图片不回传——图已展示给用户，再进上下文只会膨胀 token。
        val resultObj = result[2].getAsJsonObject("result")
        assertEquals("已截图", resultObj.get("result").asString)
        assertFalse(resultObj.has("images"))
    }

    @Test
    fun assistant_generated_images_are_rebuilt_into_model_output_blocks() {
        val result = steps(
            listOf(
                AgentMessage.UserMessage(content = "画一只猫"),
                AgentMessage.AssistantMessage(
                    content = "画好了",
                    images = listOf(AgentImage(mimeType = "image/png", base64Data = "CCC"))
                )
            )
        )
        assertEquals(InteractionStep.MODEL_OUTPUT, result[1].type())
        val content = result[1].getAsJsonArray("content")
        assertEquals(2, content.size())
        assertEquals("text", content[0].asJsonObject.get("type").asString)
        assertEquals("image", content[1].asJsonObject.get("type").asString)
        // Interactions 的 image block 平铺 mime_type / data（与 generateContent 的 inline_data 不同）
        assertEquals("image/png", content[1].asJsonObject.get("mime_type").asString)
        assertEquals("CCC", content[1].asJsonObject.get("data").asString)
    }

    @Test
    fun assistant_image_turn_abandons_snapshot_to_keep_base64_out_of_db() {
        // 图片快照若原样回放会携带大 base64（4K 图约 10MB）撑爆数据库行，
        // 因此有图片的轮放弃快照、按正文+图片重建（签名丢失影响可控）。
        val snapshot = """
            [{'type':'thought','signature':'sig-1'}]
        """.trimIndent().replace('\'', '"')
        val result = steps(
            listOf(
                AgentMessage.UserMessage(content = "画一只猫"),
                AgentMessage.AssistantMessage(
                    content = "画好了",
                    thinkingBlocksJson = snapshot,
                    images = listOf(AgentImage(mimeType = "image/png", base64Data = "CCC"))
                )
            )
        )
        assertEquals(InteractionStep.MODEL_OUTPUT, result[1].type())
        val content = result[1].getAsJsonArray("content")
        assertEquals(2, content.size())
        assertEquals("image", content[1].asJsonObject.get("type").asString)
        assertEquals("CCC", content[1].asJsonObject.get("data").asString)
    }

    @Test
    fun assistant_pure_image_turn_emits_model_output_with_image_only() {
        val result = steps(
            listOf(
                AgentMessage.UserMessage(content = "画一只猫"),
                AgentMessage.AssistantMessage(
                    content = "",
                    images = listOf(AgentImage(mimeType = "image/png", base64Data = "CCC"))
                )
            )
        )
        assertEquals(InteractionStep.MODEL_OUTPUT, result[1].type())
        val content = result[1].getAsJsonArray("content")
        assertEquals(1, content.size())
        assertEquals("image", content[0].asJsonObject.get("type").asString)
    }
}
