package com.aharou.feature.agent.domain.middleware

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 请求中间件的纯求值器：`(配置, 上下文, 请求体) -> 新请求体`，不碰网络、不碰 Android。
 */
object RequestMiddleware {

    /**
     * 禁止 [ParamOverride] 触碰的顶层字段：这些字段决定对话内容、工具面与传输语义，
     * 放开等于让一条 JSON 配置绕过 agent 的权限管控与流式约定（例如删掉 tools 让模型
     * 在无约束下作答、改 stream 让上游协议与解析器错位）。model 只允许走 [MiddlewareConfig.modelMap]，
     * 因为那一层同时保留了「对外原名」，是唯一能正确回填展示名的通道。
     */
    val PROTECTED_PATHS: Set<String> = setOf("messages", "system", "tools", "tool_choice", "stream", "model")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * null、空白或非法 JSON 一律返回 null：配置坏了只应让中间件整体失效，不该让请求发不出去。
     *
     * 额外容忍「双重编码」：有些写入路径（配置通道的字符串字段）会把整段 JSON 当成字符串再编码一次，
     * 落盘形如 "{\"modelMap\":{...}}" 。先按对象解析，失败就当成字符串解一层再试。
     */
    fun parseConfig(rawJson: String?): MiddlewareConfig? {
        var raw = rawJson?.trim().orEmpty()
        if (raw.isEmpty()) return null
        // 配置通道的字符串字段可能把 JSON 再包一层甚至两层（落盘成 "\"{...}\"" 这种），
        // 逐层剥到能按对象解析为止；剥不开就当没配，不让坏配置拦住请求。
        repeat(3) {
            runCatching { json.decodeFromString(MiddlewareConfig.serializer(), raw) }.getOrNull()
                ?.let { return it }
            raw = runCatching { json.decodeFromString(String.serializer(), raw) }.getOrNull()?.trim()
                ?: return null
            if (raw.isEmpty()) return null
        }
        return null
    }

    /**
     * 应用中间件，返回改写后的请求体。
     *
     * 需要知道哪些规则被拒/被跳过时用 [applyWithReport]。
     */
    fun apply(config: MiddlewareConfig, ctx: MiddlewareContext, body: JsonObject): JsonObject =
        applyWithReport(config, ctx, body).body

    /**
     * 与 [apply] 相同，但额外带回规则归属明细。
     *
     * 任何意外都返回原请求体（[MiddlewareResult] 里规则列表为空）：中间件是可选增强，
     * 它出问题时最坏的结果必须是「请求照旧发出去」，而不是让整个对话失败。
     */
    fun applyWithReport(config: MiddlewareConfig, ctx: MiddlewareContext, body: JsonObject): MiddlewareResult =
        runCatching { evaluate(config, ctx, body) }.getOrElse { MiddlewareResult(body) }

    private fun evaluate(config: MiddlewareConfig, ctx: MiddlewareContext, body: JsonObject): MiddlewareResult {
        if (!config.enabled) return MiddlewareResult(body)

        val actualModel = config.modelMap[ctx.requestedModel]?.takeIf { it.isNotBlank() } ?: ctx.actualModel
        var out = withModel(body, actualModel)

        // 规则按配置顺序逐条叠加，后写覆盖先写：同一条路径上后面的 SET/REMOVE 直接作用于
        // 前一条的结果，所以「先设默认值、后面按条件收紧」这类写法成立的顺序就是声明顺序。
        val applied = mutableListOf<ParamOverride>()
        val rejected = mutableListOf<ParamOverride>()
        val invalid = mutableListOf<ParamOverride>()
        for (rule in config.paramOverrides) {
            val segments = rule.path.trim().split('.').map { it.trim() }
            if (segments.any { it.isEmpty() }) {
                invalid += rule
                continue
            }
            if (PROTECTED_PATHS.contains(segments.first().lowercase())) {
                rejected += rule
                continue
            }
            if (!matches(rule, ctx, actualModel)) continue
            val updated = when (rule.op) {
                ParamOp.SET -> rule.value?.takeIf { it !is JsonNull }?.let { setPath(out, segments, it) }
                ParamOp.REMOVE -> removePath(out, segments)
            }
            if (updated == null) {
                invalid += rule
                continue
            }
            out = updated
            applied += rule
        }

        val prompt = config.systemPrompt?.trim().orEmpty()
        if (prompt.isNotEmpty() && promptTargeted(config.systemPromptTargets, ctx, actualModel)) {
            out = appendSystemPrompt(out, prompt)
        }

        return MiddlewareResult(out, applied, rejected, invalid)
    }

    private fun withModel(body: JsonObject, actualModel: String): JsonObject {
        if (actualModel.isBlank()) return body
        val current = (body["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return if (current == actualModel) body else body.withField("model", JsonPrimitive(actualModel))
    }

    /**
     * 条件匹配同时看请求模型名与映射后的模型名：配置里写 `fast` 或写 `deepseek-chat` 都算命中，
     * 否则一旦启用 modelMap，按真实模型名写的规则会全部静默失效。
     */
    private fun matches(rule: ParamOverride, ctx: MiddlewareContext, actualModel: String): Boolean {
        val model = rule.whenModel
        if (model != null && !model.equals(ctx.requestedModel, true) && !model.equals(actualModel, true)) return false
        val provider = rule.whenProvider
        if (provider != null && !provider.equals(ctx.providerId, true) && !provider.equals(ctx.providerType, true)) return false
        return true
    }

    private fun promptTargeted(targets: List<String>, ctx: MiddlewareContext, actualModel: String): Boolean {
        val normalized = targets.map { it.trim() }.filter { it.isNotEmpty() }
        if (normalized.isEmpty()) return true
        val candidates = listOf(ctx.providerId, ctx.providerType, ctx.requestedModel, actualModel)
        return normalized.any { target -> candidates.any { it.equals(target, true) } }
    }

    /** 返回 null 表示该路径上有非对象节点挡路，规则无法落地（交给调用方按「跳过」处理）。 */
    private fun setPath(body: JsonObject, segments: List<String>, value: JsonElement): JsonObject? {
        val head = segments.first()
        if (segments.size == 1) return body.withField(head, value)
        val child = when (val existing = body[head]) {
            null, is JsonNull -> JsonObject(emptyMap())
            // 已有值是标量/数组时不覆盖：为了写一个深层字段而整段重建，会把原本的数据抹掉。
            is JsonObject -> existing
            else -> return null
        }
        val newChild = setPath(child, segments.drop(1), value) ?: return null
        return body.withField(head, newChild)
    }

    private fun removePath(body: JsonObject, segments: List<String>): JsonObject {
        val head = segments.first()
        if (segments.size == 1) return if (body.containsKey(head)) JsonObject(body - head) else body
        val child = body[head] as? JsonObject ?: return body
        val newChild = removePath(child, segments.drop(1))
        return if (newChild === child) body else body.withField(head, newChild)
    }

    /**
     * 系统提示按本项目各适配器实际使用的承载位置依次尝试：`system`（字符串或 Anthropic 的
     * content block 数组）、`instructions`（Responses API）、`messages`/`input` 数组里的
     * system/developer 首条。都没有时才新建 `system` 字段。
     *
     * 这里可以写 `messages`，而 [ParamOverride] 不行：追加系统提示是用户显式开启的单一意图，
     * 只增不改既有对话内容；参数规则任意改写 messages 则会直接破坏对话与工具调用配对。
     */
    private fun appendSystemPrompt(body: JsonObject, prompt: String): JsonObject {
        when (val system = body["system"]) {
            is JsonPrimitive -> if (system.isString) {
                return body.withField("system", JsonPrimitive(joinPrompt(system.content, prompt)))
            }
            is JsonArray -> return body.withField(
                "system",
                JsonArray(system + JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(prompt))))
            )
            else -> Unit
        }

        val instructions = body["instructions"]
        if (instructions is JsonPrimitive && instructions.isString) {
            return body.withField("instructions", JsonPrimitive(joinPrompt(instructions.content, prompt)))
        }

        for (key in listOf("messages", "input")) {
            val list = body[key] as? JsonArray ?: continue
            val head = list.firstOrNull() as? JsonObject
            val role = (head?.get("role") as? JsonPrimitive)?.content
            val content = head?.get("content")
            if (head != null && (role == "system" || role == "developer") &&
                content is JsonPrimitive && content.isString
            ) {
                val newHead = head.withField("content", JsonPrimitive(joinPrompt(content.content, prompt)))
                return body.withField(key, JsonArray(listOf(newHead) + list.drop(1)))
            }
            return body.withField(
                key,
                JsonArray(listOf(JsonObject(mapOf("role" to JsonPrimitive("system"), "content" to JsonPrimitive(prompt)))) + list)
            )
        }

        return body.withField("system", JsonPrimitive(prompt))
    }

    private fun joinPrompt(existing: String, prompt: String): String =
        if (existing.isBlank()) prompt else "$existing\n\n$prompt"

    private fun JsonObject.withField(key: String, value: JsonElement): JsonObject =
        JsonObject(toMutableMap().apply { put(key, value) })
}
