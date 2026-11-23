package com.aharou.feature.plugin.runtime

import com.aharou.feature.plugin.domain.PluginHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 插件侧「用自己的功能模型跑一次对话」（`Tools.Chat.call`）。
 *
 * 走宿主的模型句柄链路：create → start → 轮询 read 直到 done。
 * 用的是插件在设置里为对应 functionType 配的那个模型，因此**每次调用都会消耗真实额度**；
 * 这里不做缓存也不做预跑，插件调几次就是几次。
 */
@Singleton
class PluginModelCallService @Inject constructor(
    private val modelService: PluginModelService,
    private val softwareSettingsService: PluginSoftwareSettingsService
) {

    suspend fun call(pluginId: String, params: JsonObject): JsonElement {
        val functionType = (params["functionType"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (functionType.isEmpty()) {
            throw PluginHostException("PARAM_INVALID", "Tools.Chat.call 需要 functionType")
        }
        val turns = params["turns"] as? JsonArray
            ?: throw PluginHostException("PARAM_INVALID", "Tools.Chat.call 需要 turns 数组")
        if (turns.isEmpty()) throw PluginHostException("PARAM_INVALID", "turns 不能为空")

        val config = softwareSettingsService.getFunctionModelConfig(
            pluginId,
            buildJsonObject { put("functionType", functionType) }
        ) as? JsonObject
        val providerId = (config?.get("configId") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val model = (config?.get("model") as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (providerId.isBlank()) {
            throw PluginHostException("MODEL_NOT_CONFIGURED", "没有为 $functionType 配置模型，去设置里指定后再试")
        }

        val messages = buildJsonArray {
            turns.forEach { element ->
                val turn = element as? JsonObject ?: return@forEach
                val kind = (turn["kind"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val content = (turn["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                add(buildJsonObject {
                    put("role", if (kind.equals("SYSTEM", ignoreCase = true)) "system" else "user")
                    put("content", content)
                })
            }
        }

        val handle = modelService.create(
            pluginId,
            buildJsonObject {
                put("providerId", providerId)
                if (model.isNotBlank()) put("model", model)
            }
        ) as? JsonObject
        val handleId = (handle?.get("handleId") as? JsonPrimitive)?.contentOrNull
            ?: throw PluginHostException("MODEL_FAILED", "创建模型句柄失败")

        try {
            val started = modelService.start(
                pluginId,
                buildJsonObject {
                    put("handleId", handleId)
                    put("messages", messages)
                },
                "plugin-chat-call"
            ) as? JsonObject
            val streamId = (started?.get("streamId") as? JsonPrimitive)?.contentOrNull
                ?: throw PluginHostException("MODEL_FAILED", "启动模型请求失败")

            val text = StringBuilder()
            var cursor = 0
            var rounds = 0
            while (rounds < MAX_READ_ROUNDS) {
                rounds += 1
                val read = modelService.read(
                    pluginId,
                    buildJsonObject {
                        put("streamId", streamId)
                        put("cursor", cursor)
                    }
                ) as? JsonObject ?: break
                (read["events"] as? JsonArray)?.forEach { event ->
                    collectText(event, text)
                }
                cursor = (read["cursor"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: cursor
                val done = (read["done"] as? JsonPrimitive)?.contentOrNull == "true" ||
                    (read["done"] as? JsonPrimitive)?.let { it.contentOrNull } == "true"
                if (done) break
            }
            return buildJsonObject {
                put("text", text.toString())
                put("functionType", functionType)
            }
        } finally {
            runCatching {
                modelService.close(pluginId, buildJsonObject { put("handleId", handleId) })
            }
        }
    }

    /** 事件形状随 provider 不同：把里面所有能当文本读的字段都收集起来。 */
    private fun collectText(event: JsonElement, sink: StringBuilder) {
        val obj = event as? JsonObject ?: run {
            (event as? JsonPrimitive)?.contentOrNull?.let { sink.append(it) }
            return
        }
        listOf("text", "delta", "content").forEach { key ->
            (obj[key] as? JsonPrimitive)?.contentOrNull?.let { value ->
                if (value.isNotEmpty()) sink.append(value)
            }
        }
    }

    private companion object {
        const val MAX_READ_ROUNDS = 600
    }
}
