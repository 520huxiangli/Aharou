package com.aharou.feature.settings.data.catalog

import com.aharou.core.net.RepoDataFetcher
import com.aharou.core.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

// 运行时模型目录抓取：GET models.dev 的全量目录，带 If-None-Match 条件请求（304 视为无变化），
// 结果过滤成只含内置官方 provider 的 JSON 后落盘。网络 / 解析失败一律返回 null，由调用方回退
// 到已有磁盘缓存与内置 assets，绝不抛给上层。
//
// HTTP 复用项目既有的 OkHttp 客户端（与 RepoDataFetcher 同一个实例，含超时与代理认证配置），
// 不引入新依赖。
@Singleton
class ModelCatalogClient @Inject constructor(
    private val cache: ModelCatalogCache
) {
    data class Snapshot(val body: String, val fetchedAt: Long, val fromCache: Boolean)

    // 目录地址可配置：默认与 scripts/update-models-dev-assets.py 同源，便于之后接设置项或测试替换。
    @Volatile
    var catalogUrl: String = DEFAULT_CATALOG_URL

    // 串行化抓取：并发调用会排队而不是直接放弃，手动刷新与后台刷新同时触发时不会互相拿到 null。
    private val fetchLock = Mutex()

    suspend fun fetch(url: String = catalogUrl): Snapshot? = withContext(Dispatchers.IO) {
        fetchLock.withLock {
            val cached = cache.read()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .apply {
                    cached?.etag?.takeIf { it.isNotBlank() }?.let { header("If-None-Match", it) }
                }
                .get()
                .build()
            try {
                RepoDataFetcher.DEFAULT_CLIENT.newCall(request).execute().use { response ->
                    when {
                        // 304：正文没变，只把抓取时间刷新一下，别让缓存被一直判成过期。
                        response.code == 304 && cached != null -> {
                            cache.markValidated()
                            Snapshot(cached.body, System.currentTimeMillis(), fromCache = true)
                        }

                        response.isSuccessful -> {
                            val raw = response.body?.string().orEmpty()
                            if (raw.isBlank()) {
                                FileLogger.w(TAG, "模型目录正文为空 code=${response.code}")
                                null
                            } else {
                                val filtered = filterCatalog(raw)
                                if (filtered == null) {
                                    FileLogger.w(TAG, "模型目录解析失败（保留旧缓存）chars=${raw.length}")
                                    null
                                } else {
                                    val etag = response.header("ETag")
                                    if (!cache.write(filtered, etag)) null
                                    else Snapshot(filtered, System.currentTimeMillis(), fromCache = false)
                                }
                            }
                        }

                        else -> {
                            FileLogger.w(TAG, "模型目录请求失败 code=${response.code}")
                            null
                        }
                    }
                }
            } catch (t: Throwable) {
                FileLogger.w(TAG, "模型目录拉取异常", t)
                null
            }
        }
    }

    companion object {
        const val TAG = "ModelCatalogClient"

        // 与 scripts/update-models-dev-assets.py 同源。
        const val DEFAULT_CATALOG_URL = "https://models.dev/api.json"
        private const val USER_AGENT = "aharou-android"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        // 与内置 assets/api.official.json 的 12 个官方 provider 一致：运行时目录不引入新 provider。
        val OFFICIAL_PROVIDER_IDS: Set<String> = setOf(
            "anthropic", "deepseek", "google", "xai",
            "minimax", "minimax-cn", "moonshotai", "moonshotai-cn",
            "openai", "alibaba", "alibaba-cn", "zhipuai"
        )

        // 目录里会被 ModelMetadataService.parseCatalog 读到、且必须是对应类型的字段。
        // 类型不符的字段直接剔除，而不是让整份目录解析失败（models.dev 未来加字段 / 改形态时宽松降级）。
        private val STRING_FIELDS = listOf("id", "name")
        private val BOOLEAN_FIELDS = listOf("tool_call", "reasoning", "temperature")
        private val NUMBER_OBJECT_FIELDS = listOf("limit", "cost")

        // 宽松过滤：只保留内置官方 provider，模型条目逐个校验；整体结构非法或过滤后为空返回 null。
        fun filterCatalog(raw: String): String? = try {
            val root = json.parseToJsonElement(raw)
            filterCatalog(root)
        } catch (t: Throwable) {
            FileLogger.w(TAG, "模型目录 JSON 解析失败", t)
            null
        }

        private fun filterCatalog(root: JsonElement): String? {
            val rootObj = root as? JsonObject ?: return null
            val kept = LinkedHashMap<String, JsonElement>()
            var keptModelCount = 0
            for ((providerId, providerEl) in rootObj) {
                if (providerId.lowercase() !in OFFICIAL_PROVIDER_IDS) continue
                val providerObj = providerEl as? JsonObject
                if (providerObj == null) {
                    FileLogger.d(TAG, "跳过结构非法的 provider=$providerId")
                    continue
                }
                val modelsObj = providerObj["models"] as? JsonObject
                if (modelsObj == null) {
                    FileLogger.d(TAG, "跳过缺少 models 的 provider=$providerId")
                    continue
                }
                val keptModels = LinkedHashMap<String, JsonElement>()
                var skippedModels = 0
                for ((modelKey, modelEl) in modelsObj) {
                    val modelObj = modelEl as? JsonObject
                    if (modelObj == null) {
                        skippedModels++
                        continue
                    }
                    keptModels[modelKey] = sanitizeModel(modelObj)
                }
                if (skippedModels > 0) {
                    FileLogger.w(TAG, "跳过结构非法的模型条目 provider=$providerId count=$skippedModels")
                }
                val sanitizedProvider = LinkedHashMap(providerObj)
                sanitizedProvider["models"] = JsonObject(keptModels)
                kept[providerId] = JsonObject(sanitizedProvider)
                keptModelCount += keptModels.size
            }
            if (kept.isEmpty() || keptModelCount == 0) {
                FileLogger.w(TAG, "过滤后没有任何内置 provider / 模型，视为目录不可用")
                return null
            }
            return JsonObject(kept).toString()
        }

        // 逐字段校验：已知字段类型不符就剔掉该字段（解析时按缺字段降级），未知字段原样保留。
        private fun sanitizeModel(model: JsonObject): JsonObject {
            val out = LinkedHashMap(model)
            for (key in STRING_FIELDS) {
                val value = out[key] ?: continue
                if (value !is JsonPrimitive || !value.isString) out.remove(key)
            }
            for (key in BOOLEAN_FIELDS) {
                val value = out[key] ?: continue
                if (value !is JsonPrimitive || value.booleanOrNull == null) out.remove(key)
            }
            for (key in NUMBER_OBJECT_FIELDS) {
                val value = out[key]
                if (value != null) {
                    val obj = value as? JsonObject
                    if (obj == null) out.remove(key) else out[key] = JsonObject(obj.filterValues { it is JsonPrimitive })
                }
            }
            out["modalities"]?.let { value ->
                val obj = value as? JsonObject
                if (obj == null) out.remove("modalities")
                else {
                    val arrays = obj.filterValues { it is JsonArray }
                        .mapValues { (_, v) -> JsonArray((v as JsonArray).filter { it is JsonPrimitive }) }
                    out["modalities"] = JsonObject(arrays)
                }
            }
            out["reasoning_options"]?.let { value ->
                val arr = value as? JsonArray
                if (arr == null) {
                    out.remove("reasoning_options")
                } else {
                    val cleaned = arr.mapNotNull { item -> (item as? JsonObject)?.let { sanitizeReasoningOption(it) } }
                    if (cleaned.isEmpty()) out.remove("reasoning_options")
                    else out["reasoning_options"] = JsonArray(cleaned)
                }
            }
            return JsonObject(out)
        }

        // reasoning_options 单项：type 必须是字符串，values 必须是字符串数组，min / max 必须是数字。
        private fun sanitizeReasoningOption(option: JsonObject): JsonObject {
            val out = LinkedHashMap<String, JsonElement>()
            option["type"]?.let { v ->
                if (v is JsonPrimitive && v.isString) out["type"] = v
            }
            option["values"]?.let { v ->
                if (v is JsonArray) {
                    out["values"] = JsonArray(v.filter { it is JsonPrimitive && it.isString })
                }
            }
            for (key in listOf("min", "max")) {
                option[key]?.let { v ->
                    if (v is JsonPrimitive) out[key] = v
                }
            }
            return JsonObject(out)
        }
    }
}
