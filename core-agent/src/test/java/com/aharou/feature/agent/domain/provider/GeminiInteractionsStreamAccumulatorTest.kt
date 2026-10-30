package com.aharou.feature.agent.domain.provider

import com.aharou.feature.agent.data.remote.gemini.InteractionStep
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Interactions 流式事件解析（[GeminiInteractionsStreamAccumulator]）与非流式 steps 解析。
 *
 * 覆盖点：结束按 interaction status 到终态判定（官方不发 `[DONE]`，且工具轮停在
 * `requires_action` 不会再发 `completed`）、工具入参逐字聚合、思考签名进快照、
 * 以及 usage 的「输出不含思考、需相加」。
 */
class GeminiInteractionsStreamAccumulatorTest {

    /** 事件 JSON 用单引号书写，避免层层转义；解析前替换为双引号。 */
    private fun event(json: String): JsonObject =
        JsonParser.parseString(json.replace('\'', '"')).asJsonObject

    private fun feed(acc: GeminiInteractionsStreamAccumulator, vararg events: String): List<InteractionsDelta> =
        events.mapNotNull { acc.accept(event(it)) }

    private fun snapshotOf(response: AIResponse) =
        JsonParser.parseString(response.thinkingBlocksJson!!).asJsonArray

    @Test
    fun text_deltas_accumulate_and_stream_out() {
        val acc = GeminiInteractionsStreamAccumulator()
        val deltas = feed(
            acc,
            "{'event_type':'step.start','index':1,'step':{'type':'model_output'}}",
            "{'event_type':'step.delta','index':1,'delta':{'type':'text','text':'AI '}}",
            "{'event_type':'step.delta','index':1,'delta':{'type':'text','text':'works'}}",
            "{'event_type':'step.stop','index':1}",
            "{'event_type':'interaction.completed','interaction':{'id':'i1','status':'completed'}}"
        )
        assertEquals(listOf("AI ", "works"), deltas.map { (it as InteractionsDelta.Text).text })
        assertTrue(acc.terminated)
        val response = acc.toResponse()
        assertEquals("AI works", response.content)
        assertEquals("completed", response.stopReason)
        // 纯文本轮无需快照，避免与正文双写
        assertNull(response.thinkingBlocksJson)
    }

    @Test
    fun thought_summary_streams_as_reasoning_and_signature_lands_in_snapshot() {
        val acc = GeminiInteractionsStreamAccumulator()
        val deltas = feed(
            acc,
            "{'event_type':'step.start','index':0,'step':{'type':'thought'}}",
            "{'event_type':'step.delta','index':0,'delta':{'type':'thought_summary','content':{'type':'text','text':'先看文件'}}}",
            "{'event_type':'step.delta','index':0,'delta':{'type':'thought_signature','signature':'sig-1'}}",
            "{'event_type':'step.stop','index':0}",
            "{'event_type':'step.start','index':1,'step':{'type':'model_output'}}",
            "{'event_type':'step.delta','index':1,'delta':{'type':'text','text':'好'}}",
            "{'event_type':'interaction.completed','interaction':{'status':'completed'}}"
        )
        assertEquals("先看文件", (deltas[0] as InteractionsDelta.Reasoning).text)
        val response = acc.toResponse()
        assertEquals("先看文件", response.reasoning)
        assertEquals("好", response.content)
        val snapshot = snapshotOf(response)
        assertEquals(2, snapshot.size())
        assertEquals(InteractionStep.THOUGHT, snapshot[0].asJsonObject.get("type").asString)
        // signature 不可重建，必须进快照回传
        assertEquals("sig-1", snapshot[0].asJsonObject.get("signature").asString)
        assertEquals(InteractionStep.MODEL_OUTPUT, snapshot[1].asJsonObject.get("type").asString)
    }

    @Test
    fun thought_delta_with_inline_text_is_also_accepted() {
        val acc = GeminiInteractionsStreamAccumulator()
        // 迁移指南示例把思考文本直接铺在 delta.text 上
        val deltas = feed(
            acc,
            "{'event_type':'step.start','index':0,'step':{'type':'thought'}}",
            "{'event_type':'step.delta','index':0,'delta':{'type':'thought','text':'思考中'}}"
        )
        assertEquals("思考中", (deltas.single() as InteractionsDelta.Reasoning).text)
    }

    @Test
    fun function_call_name_is_declared_at_step_start() {
        val acc = GeminiInteractionsStreamAccumulator()

        // 名字在 step.start 就到达：UI 靠它把「正在思考」提前换成具体场景
        val declared = acc.accept(
            event("{'event_type':'step.start','index':1,'step':{'type':'function_call','id':'fc_1','name':'readFile'}}")
        )
        assertEquals("readFile", (declared as InteractionsDelta.ToolCallDeclared).name)

        // 同一个 step 只播报一次，参数增量不产生声明
        assertNull(
            acc.accept(event("{'event_type':'step.delta','index':1,'delta':{'type':'arguments_delta','arguments':'{}'}}"))
        )

        // 声明不能打断本 step 的其他解析：step.start 带的完整 arguments 仍要落进聚合结果
        val withArgs = GeminiInteractionsStreamAccumulator()
        withArgs.accept(
            event("{'event_type':'step.start','index':1,'step':{'type':'function_call','id':'fc_2','name':'readFile','arguments':{'path':'b.kt'}}}")
        )
        withArgs.accept(event("{'event_type':'interaction.requires_action','interaction':{'status':'requires_action'}}"))
        val call = withArgs.toResponse().toolCalls.single()
        assertEquals("readFile", call.name)
        assertEquals("b.kt", call.arguments["path"].toString().trim('"'))
    }

    @Test
    fun function_call_arguments_are_accumulated_from_deltas() {
        val acc = GeminiInteractionsStreamAccumulator()
        feed(
            acc,
            "{'event_type':'step.start','index':1,'step':{'type':'function_call','id':'fc_1','name':'readFile'}}",
            "{'event_type':'step.delta','index':1,'delta':{'type':'arguments_delta','arguments':'{\\'path\\':'}}",
            "{'event_type':'step.delta','index':1,'delta':{'type':'arguments_delta','arguments':'\\'a.kt\\'}'}}",
            "{'event_type':'step.stop','index':1,'status':'waiting'}",
            "{'event_type':'interaction.requires_action','interaction':{'status':'requires_action'}}"
        )
        val response = acc.toResponse()
        val call = response.toolCalls.single()
        assertEquals("fc_1", call.id)
        assertEquals("readFile", call.name)
        assertEquals("a.kt", call.arguments["path"].toString().trim('"'))
        assertEquals("requires_action", response.stopReason)
    }

    @Test
    fun requires_action_terminates_the_stream() {
        val acc = GeminiInteractionsStreamAccumulator()
        feed(acc, "{'event_type':'interaction.created','interaction':{'status':'in_progress'}}")
        assertFalse(acc.terminated)
        // 工具轮停在 requires_action，服务端不会再发 completed；只等 completed 会误判成断流
        feed(acc, "{'event_type':'interaction.status_update','interaction_id':'i1','status':'requires_action'}")
        assertTrue(acc.terminated)
    }

    @Test
    fun status_written_only_in_the_event_name_is_still_recognized() {
        val acc = GeminiInteractionsStreamAccumulator()
        feed(acc, "{'event_type':'interaction.requires_action','interaction':{'id':'i1'}}")
        assertTrue(acc.terminated)
        assertEquals("requires_action", acc.toResponse().stopReason)
    }

    @Test
    fun migration_guide_style_type_field_is_accepted() {
        val acc = GeminiInteractionsStreamAccumulator()
        // 迁移指南的 REST 示例用 `type` 而非参考文档的 `event_type`
        val deltas = feed(
            acc,
            "{'type':'step.start','index':0,'step':{'type':'model_output'}}",
            "{'type':'step.delta','index':0,'delta':{'type':'text','text':'hi'}}",
            "{'type':'interaction.completed','interaction':{'status':'completed'}}"
        )
        assertEquals("hi", (deltas.single() as InteractionsDelta.Text).text)
        assertTrue(acc.terminated)
    }

    @Test
    fun usage_adds_thought_tokens_into_output() {
        val acc = GeminiInteractionsStreamAccumulator()
        feed(
            acc,
            "{'event_type':'interaction.completed','interaction':{'status':'completed','usage':" +
                "{'total_input_tokens':7,'total_output_tokens':20,'total_thought_tokens':22,'total_cached_tokens':3}}}"
        )
        val response = acc.toResponse()
        assertEquals(7, response.inputTokens)
        // total_output_tokens 不含思考，而思考按输出价计费（官方示例 total = 7+20+22 = 49）
        assertEquals(42, response.outputTokens)
        assertEquals(3, response.cachedInputTokens)
    }

    @Test
    fun incomplete_status_is_treated_as_truncation() {
        val acc = GeminiInteractionsStreamAccumulator()
        feed(
            acc,
            "{'event_type':'step.start','index':0,'step':{'type':'model_output'}}",
            "{'event_type':'step.delta','index':0,'delta':{'type':'text','text':'半句'}}",
            "{'event_type':'interaction.completed','interaction':{'status':'incomplete'}}"
        )
        // 撞输出上限的语义从 finishReason=MAX_TOKENS 变成 status=incomplete，
        // 归一到 length 才能触发上层续写，否则表现为回答被无声截断
        val response = acc.toResponse()
        assertEquals("length", response.stopReason)
        assertTrue(response.isTruncated)
    }

    @Test
    fun failed_status_carries_error_message_for_the_user() {
        val acc = GeminiInteractionsStreamAccumulator()
        feed(
            acc,
            "{'event_type':'interaction.completed','interaction':{'status':'failed'," +
                "'errors':[{'code':'internal','message':'后端开小差了'}]}}"
        )
        val response = acc.toResponse()
        assertTrue(response.isAborted)
        // failed 时正文通常为空，静默结束会让用户看到空白气泡
        assertEquals("后端开小差了", response.stopDetail)
    }

    @Test
    fun error_event_throws_for_retry_classification() {
        val acc = GeminiInteractionsStreamAccumulator()
        val error = runCatching {
            acc.accept(event("{'event_type':'error','error':{'code':'not_found','message':'没找到'}}"))
        }.exceptionOrNull()
        assertTrue(error is StreamApiException)
        assertEquals("not_found", (error as StreamApiException).code)
    }

    @Test
    fun non_stream_steps_parse_text_thought_and_function_call() {
        val steps = JsonParser.parseString(
            """
            [{'type':'thought','signature':'sig-1','summary':[{'type':'text','text':'想一下'}]},
             {'type':'model_output','content':[{'type':'text','text':'给你'}]},
             {'type':'function_call','id':'fc_1','name':'readFile','arguments':{'path':'a.kt'}}]
            """.trimIndent().replace('\'', '"')
        ).asJsonArray

        val parsed = parseInteractionSteps(steps)
        assertEquals("给你", parsed.text)
        assertEquals("想一下", parsed.reasoning)
        val call = parsed.toolCalls.single()
        assertEquals("fc_1", call.id)
        assertEquals("readFile", call.name)
        assertEquals("a.kt", call.arguments["path"].toString().trim('"'))
        // 有 thought / function_call 就必须留快照回传
        assertEquals(3, JsonParser.parseString(parsed.stepsSnapshotJson!!).asJsonArray.size())
    }

    @Test
    fun stream_falls_back_to_steps_carried_by_the_terminal_event() {
        val acc = GeminiInteractionsStreamAccumulator()
        // 中途 delta 全丢时，终止事件带的 steps 用于兜底补齐
        feed(
            acc,
            "{'event_type':'interaction.completed','interaction':{'status':'completed','steps':" +
                "[{'type':'model_output','content':[{'type':'text','text':'兜底正文'}]}]}}"
        )
        assertEquals("兜底正文", acc.toResponse().content)
    }

    @Test
    fun usage_riding_on_step_delta_metadata_is_absorbed() {
        val acc = GeminiInteractionsStreamAccumulator()
        feed(
            acc,
            "{'event_type':'step.delta','index':0,'delta':{'type':'text','text':'hi'}," +
                "'metadata':{'total_usage':{'total_input_tokens':5,'total_output_tokens':2}}}"
        )
        assertEquals(5, acc.toResponse().inputTokens)
    }

    @Test
    fun parse_steps_extracts_image_blocks_from_model_output() {
        val steps = JsonParser.parseString(
            """
            [{'type':'model_output','content':[
              {'type':'text','text':'画好了'},
              {'type':'image','mime_type':'image/png','data':'AAAABB=='}]}]
            """.trimIndent().replace('\'', '"')
        ).asJsonArray
        val output = parseInteractionSteps(steps)
        assertEquals("画好了", output.text)
        assertEquals(1, output.images.size)
        assertEquals("image/png", output.images[0].mimeType)
        assertEquals("AAAABB==", output.images[0].base64Data)
    }

    @Test
    fun stream_start_event_with_image_content_lands_in_response_images() {
        val acc = GeminiInteractionsStreamAccumulator()
        // 流式下图片整块随 step.start 的完整载荷到达，不产生流式增量
        feed(
            acc,
            "{'event_type':'step.start','index':0,'step':{'type':'model_output','content':[" +
                "{'type':'text','text':'图来'},{'type':'image','mime_type':'image/png','data':'QUJD'}]}}",
            "{'event_type':'step.stop','index':0}",
            "{'event_type':'interaction.completed','interaction':{'status':'completed','steps':[]}}"
        )
        val response = acc.toResponse()
        assertEquals("图来", response.content)
        assertEquals(1, response.images.size)
        assertEquals("image/png", response.images[0].mimeType)
        assertEquals("QUJD", response.images[0].base64Data)
    }

    @Test
    fun terminal_event_steps_with_images_are_the_fallback() {
        val acc = GeminiInteractionsStreamAccumulator()
        // step.start 未带图时，终止事件携带的 steps 兜底解析图片
        feed(
            acc,
            "{'event_type':'interaction.completed','interaction':{'status':'completed','steps':" +
                "[{'type':'model_output','content':[{'type':'image','mime_type':'image/jpeg','data':'MTIz'}]}]}}"
        )
        val response = acc.toResponse()
        assertEquals(1, response.images.size)
        assertEquals("image/jpeg", response.images[0].mimeType)
        assertEquals("MTIz", response.images[0].base64Data)
    }
}
