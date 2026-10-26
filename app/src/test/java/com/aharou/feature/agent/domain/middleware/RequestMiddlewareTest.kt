package com.aharou.feature.agent.domain.middleware

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestMiddlewareTest {

    private val ctx = MiddlewareContext(
        providerId = "prov-1",
        providerType = "openai",
        requestedModel = "fast"
    )

    private fun body(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    private fun JsonObject.num(key: String): Double = this[key]!!.jsonPrimitive.content.toDouble()

    private val baseBody = """
        {"model":"fast","messages":[{"role":"user","content":"hi"}],"stream":true,"temperature":1.0}
    """.trimIndent()

    // ---------- modelMap ----------

    @Test
    fun modelMap_rewrites_outgoing_model_but_keeps_requested_name_in_context() {
        val config = MiddlewareConfig(modelMap = mapOf("fast" to "deepseek-chat"))
        val out = RequestMiddleware.apply(config, ctx, body(baseBody))

        assertEquals("deepseek-chat", out.str("model"))
        // 回复侧要显示原名，所以上下文里的 requestedModel 不能被求值器改掉
        assertEquals("fast", ctx.requestedModel)
    }

    @Test
    fun modelMap_without_match_leaves_model_untouched() {
        val config = MiddlewareConfig(modelMap = mapOf("slow" to "some-model"))
        val out = RequestMiddleware.apply(config, ctx, body(baseBody))

        assertEquals("fast", out.str("model"))
    }

    @Test
    fun context_actual_model_is_used_when_caller_already_resolved_mapping() {
        val resolved = ctx.copy(actualModel = "resolved-model")
        val out = RequestMiddleware.apply(MiddlewareConfig(), resolved, body(baseBody))

        assertEquals("resolved-model", out.str("model"))
    }

    // ---------- paramOverride ----------

    @Test
    fun paramOverride_set_flat_field() {
        val config = MiddlewareConfig(
            paramOverrides = listOf(ParamOverride(path = "temperature", value = JsonPrimitive(0.2)))
        )
        val out = RequestMiddleware.apply(config, ctx, body(baseBody))

        assertEquals(0.2, out.num("temperature"), 0.0)
    }

    @Test
    fun paramOverride_set_nested_path_creates_missing_objects() {
        val config = MiddlewareConfig(
            paramOverrides = listOf(ParamOverride(path = "reasoning.effort", value = JsonPrimitive("low")))
        )
        val out = RequestMiddleware.apply(config, ctx, body(baseBody))

        assertEquals("low", out["reasoning"]!!.jsonObject.str("effort"))
        // 顶层既有字段不能被这条规则带走
        assertEquals("fast", out.str("model"))
        assertTrue(out.containsKey("messages"))
    }

    @Test
    fun paramOverride_set_nested_path_keeps_sibling_keys() {
        val raw = """{"model":"fast","reasoning":{"effort":"high","summary":"auto"}}"""
        val config = MiddlewareConfig(
            paramOverrides = listOf(ParamOverride(path = "reasoning.effort", value = JsonPrimitive("low")))
        )
        val out = RequestMiddleware.apply(config, ctx, body(raw))

        assertEquals("low", out["reasoning"]!!.jsonObject.str("effort"))
        assertEquals("auto", out["reasoning"]!!.jsonObject.str("summary"))
    }

    @Test
    fun paramOverride_remove_flat_field() {
        val config = MiddlewareConfig(
            paramOverrides = listOf(ParamOverride(path = "temperature", op = ParamOp.REMOVE))
        )
        val out = RequestMiddleware.apply(config, ctx, body(baseBody))

        assertFalse(out.containsKey("temperature"))
        assertEquals("fast", out.str("model"))
    }

    @Test
    fun paramOverride_remove_nested_field() {
        val raw = """{"model":"fast","reasoning":{"effort":"high","summary":"auto"}}"""
        val config = MiddlewareConfig(
            paramOverrides = listOf(ParamOverride(path = "reasoning.effort", op = ParamOp.REMOVE))
        )
        val out = RequestMiddleware.apply(config, ctx, body(raw))

        assertFalse(out["reasoning"]!!.jsonObject.containsKey("effort"))
        assertEquals("auto", out["reasoning"]!!.jsonObject.str("summary"))
    }

    @Test
    fun paramOverride_remove_absent_key_is_noop() {
        val config = MiddlewareConfig(
            paramOverrides = listOf(ParamOverride(path = "top_p", op = ParamOp.REMOVE))
        )
        val out = RequestMiddleware.apply(config, ctx, body(baseBody))

        assertEquals(body(baseBody), out)
    }

    @Test
    fun paramOverride_set_without_scalar_intermediate_is_skipped_without_destroying_body() {
        val raw = """{"model":"fast","reasoning":"high"}"""
        val report = RequestMiddleware.applyWithReport(
            MiddlewareConfig(
                paramOverrides = listOf(ParamOverride(path = "reasoning.effort", value = JsonPrimitive("low")))
            ),
            ctx,
            body(raw)
        )

        assertEquals(body(raw), report.body)
        assertEquals(1, report.invalid.size)
        assertTrue(report.applied.isEmpty())
    }

    @Test
    fun paramOverride_set_without_value_is_invalid() {
        val report = RequestMiddleware.applyWithReport(
            MiddlewareConfig(paramOverrides = listOf(ParamOverride(path = "temperature"))),
            ctx,
            body(baseBody)
        )

        assertEquals(body(baseBody), report.body)
        assertEquals(1, report.invalid.size)
    }

    // ---------- conditions ----------

    @Test
    fun whenModel_matches_requested_name() {
        val config = MiddlewareConfig(
            paramOverrides = listOf(
                ParamOverride(path = "temperature", value = JsonPrimitive(0.2), whenModel = "fast")
            )
        )
        assertEquals(0.2, RequestMiddleware.apply(config, ctx, body(baseBody)).num("temperature"), 0.0)
    }

    @Test
    fun whenModel_matches_mapped_name_too() {
        val config = MiddlewareConfig(
            modelMap = mapOf("fast" to "deepseek-chat"),
            paramOverrides = listOf(
                ParamOverride(path = "temperature", value = JsonPrimitive(0.2), whenModel = "deepseek-chat")
            )
        )
        assertEquals(0.2, RequestMiddleware.apply(config, ctx, body(baseBody)).num("temperature"), 0.0)
    }

    @Test
    fun whenProvider_matches_id_and_type() {
        val byId = MiddlewareConfig(
            paramOverrides = listOf(
                ParamOverride(path = "max_tokens", value = JsonPrimitive(256), whenProvider = "prov-1")
            )
        )
        val byType = MiddlewareConfig(
            paramOverrides = listOf(
                ParamOverride(path = "max_tokens", value = JsonPrimitive(256), whenProvider = "openai")
            )
        )
        assertEquals(256.0, RequestMiddleware.apply(byId, ctx, body(baseBody)).num("max_tokens"), 0.0)
        assertEquals(256.0, RequestMiddleware.apply(byType, ctx, body(baseBody)).num("max_tokens"), 0.0)
    }

    @Test
    fun condition_mismatch_skips_rule_and_keeps_body() {
        val config = MiddlewareConfig(
            paramOverrides = listOf(
                ParamOverride(path = "temperature", value = JsonPrimitive(0.2), whenModel = "slow"),
                ParamOverride(path = "max_tokens", value = JsonPrimitive(1), whenProvider = "anthropic")
            )
        )
        val report = RequestMiddleware.applyWithReport(config, ctx, body(baseBody))

        assertEquals(body(baseBody), report.body)
        assertTrue(report.applied.isEmpty())
        assertTrue(report.rejected.isEmpty())
    }

    // ---------- 顺序约定 ----------

    @Test
    fun later_rule_overrides_earlier_rule_on_same_path() {
        val config = MiddlewareConfig(
            paramOverrides = listOf(
                ParamOverride(path = "temperature", value = JsonPrimitive(0.2)),
                ParamOverride(path = "temperature", value = JsonPrimitive(0.9))
            )
        )
        assertEquals(0.9, RequestMiddleware.apply(config, ctx, body(baseBody)).num("temperature"), 0.0)
    }

    @Test
    fun later_remove_wins_over_earlier_set() {
        val config = MiddlewareConfig(
            paramOverrides = listOf(
                ParamOverride(path = "top_p", value = JsonPrimitive(0.5)),
                ParamOverride(path = "top_p", op = ParamOp.REMOVE)
            )
        )
        val out = RequestMiddleware.apply(config, ctx, body(baseBody))

        assertFalse(out.containsKey("top_p"))
    }

    @Test
    fun later_set_wins_over_earlier_remove() {
        val config = MiddlewareConfig(
            paramOverrides = listOf(
                ParamOverride(path = "temperature", op = ParamOp.REMOVE),
                ParamOverride(path = "temperature", value = JsonPrimitive(0.3))
            )
        )
        assertEquals(0.3, RequestMiddleware.apply(config, ctx, body(baseBody)).num("temperature"), 0.0)
    }

    // ---------- 安全边界 ----------

    @Test
    fun protected_license_fields_are_all_rejected() {
        val rules = listOf(
            ParamOverride(path = "messages", op = ParamOp.REMOVE),
            ParamOverride(path = "system", value = JsonPrimitive("hijack")),
            ParamOverride(path = "tools", op = ParamOp.REMOVE),
            ParamOverride(path = "tool_choice", value = JsonPrimitive("none")),
            ParamOverride(path = "stream", value = JsonPrimitive(false)),
            ParamOverride(path = "model", value = JsonPrimitive("evil-model"))
        )
        val report = RequestMiddleware.applyWithReport(MiddlewareConfig(paramOverrides = rules), ctx, body(baseBody))

        // 请求其余部分完全不变
        assertEquals(body(baseBody), report.body)
        assertTrue(report.applied.isEmpty())
        assertEquals(6, report.rejected.size)
        assertTrue(
            RequestMiddleware.PROTECTED_PATHS.containsAll(
                listOf("messages", "system", "tools", "tool_choice", "stream", "model")
            )
        )
    }

    @Test
    fun nested_path_under_protected_field_is_rejected_too() {
        val report = RequestMiddleware.applyWithReport(
            MiddlewareConfig(
                paramOverrides = listOf(ParamOverride(path = "messages.0.content", value = JsonPrimitive("hijack")))
            ),
            ctx,
            body(baseBody)
        )

        assertEquals(body(baseBody), report.body)
        assertEquals(1, report.rejected.size)
    }

    @Test
    fun protected_check_is_case_insensitive() {
        val report = RequestMiddleware.applyWithReport(
            MiddlewareConfig(paramOverrides = listOf(ParamOverride(path = "Messages", op = ParamOp.REMOVE))),
            ctx,
            body(baseBody)
        )
        assertEquals(1, report.rejected.size)
    }

    // ---------- systemPrompt ----------

    @Test
    fun systemPrompt_appends_to_existing_string_system() {
        val raw = """{"model":"fast","system":"be nice"}"""
        val out = RequestMiddleware.apply(MiddlewareConfig(systemPrompt = "answer in JSON"), ctx, body(raw))

        assertEquals("be nice\n\nanswer in JSON", out.str("system"))
    }

    @Test
    fun systemPrompt_prepends_message_when_messages_have_no_system_head() {
        val out = RequestMiddleware.apply(MiddlewareConfig(systemPrompt = "answer in JSON"), ctx, body(baseBody))

        val messages = out["messages"] as JsonArray
        assertEquals(2, messages.size)
        val head = messages[0].jsonObject
        assertEquals("system", head.str("role"))
        assertEquals("answer in JSON", head.str("content"))
    }

    @Test
    fun systemPrompt_respects_target_list() {
        val hit = MiddlewareConfig(systemPrompt = "answer in JSON", systemPromptTargets = listOf("openai"))
        val miss = MiddlewareConfig(systemPrompt = "answer in JSON", systemPromptTargets = listOf("anthropic"))

        assertEquals(2, (RequestMiddleware.apply(hit, ctx, body(baseBody))["messages"] as JsonArray).size)
        assertEquals(body(baseBody), RequestMiddleware.apply(miss, ctx, body(baseBody)))
    }

    @Test
    fun blank_system_prompt_is_ignored() {
        val out = RequestMiddleware.apply(MiddlewareConfig(systemPrompt = "   "), ctx, body(baseBody))
        assertEquals(body(baseBody), out)
    }

    // ---------- 配置解析与健壮性 ----------

    @Test
    fun parseConfig_returns_null_for_null_blank_or_invalid_json() {
        assertNull(RequestMiddleware.parseConfig(null))
        assertNull(RequestMiddleware.parseConfig(""))
        assertNull(RequestMiddleware.parseConfig("   "))
        assertNull(RequestMiddleware.parseConfig("{not json"))
        assertNull(RequestMiddleware.parseConfig("""{"paramOverrides":[{"op":"BROKEN"}]}"""))
    }

    @Test
    fun parseConfig_reads_full_config() {
        val raw = """
            {
              "modelMap": {"fast": "deepseek-chat"},
              "paramOverrides": [{"path":"temperature","op":"SET","value":0.2,"whenModel":"fast"}],
              "systemPrompt": "answer in JSON",
              "systemPromptTargets": ["openai"],
              "enabled": true
            }
        """.trimIndent()
        val config = RequestMiddleware.parseConfig(raw) ?: throw AssertionError("配置应能解析")

        assertEquals("deepseek-chat", config.modelMap["fast"])
        assertEquals(ParamOp.SET, config.paramOverrides.single().op)
        assertEquals("answer in JSON", config.systemPrompt)
        assertEquals(listOf("openai"), config.systemPromptTargets)
        assertTrue(config.enabled)
    }

    @Test
    fun disabled_config_returns_body_unchanged() {
        val config = MiddlewareConfig(
            modelMap = mapOf("fast" to "deepseek-chat"),
            paramOverrides = listOf(ParamOverride(path = "temperature", value = JsonPrimitive(0.2))),
            systemPrompt = "answer in JSON",
            enabled = false
        )
        assertEquals(body(baseBody), RequestMiddleware.apply(config, ctx, body(baseBody)))
    }

    @Test
    fun original_body_is_not_mutated() {
        val original = body(baseBody)
        val snapshot = original.toString()
        RequestMiddleware.apply(
            MiddlewareConfig(
                paramOverrides = listOf(ParamOverride(path = "reasoning.effort", value = JsonPrimitive("low")))
            ),
            ctx,
            original
        )

        assertEquals(snapshot, original.toString())
    }

    @Test
    fun parseConfig_acceptsDoubleEncodedJson() {
        // 配置通道的字符串字段可能把整段 JSON 再编码一次，落盘成 "{\"systemPrompt\":\"x\"}"
        val wrapped = "\"{\\\"systemPrompt\\\":\\\"先输出[MW-OK]\\\"}\""
        val config = RequestMiddleware.parseConfig(wrapped)
        assertEquals("先输出[MW-OK]", config?.systemPrompt)
    }

    @Test
    fun parseConfig_acceptsTripleEncodedJson() {
        // 配置通道实际落盘可能出现两层字符串包装
        val triple = "\"\\\"{\\\\\\\"systemPrompt\\\\\\\":\\\\\\\"三层\\\\\\\"}\\\"\""
        assertEquals("三层", RequestMiddleware.parseConfig(triple)?.systemPrompt)
    }
}
