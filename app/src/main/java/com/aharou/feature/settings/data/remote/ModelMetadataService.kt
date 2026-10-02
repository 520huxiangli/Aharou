package com.aharou.feature.settings.data.remote

import android.content.Context
import com.aharou.core.net.RepoDataFetcher
import com.aharou.core.util.FileLogger
import com.aharou.feature.settings.data.local.CustomModelMetadataStore
import com.aharou.feature.settings.domain.model.ModelMetadata
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.model.mergeModelMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelMetadataService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val customModelMetadataStore: CustomModelMetadataStore
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 统一拉取器；模型元数据每日由 CI 发到独立的 data 分支，main 上不再存机器产物。 */
    private val repoFetcher = RepoDataFetcher(context, branch = "data")

    @Volatile
    private var cached: Catalog? = null

    @Volatile
    private var refreshAttemptedThisProcess = false

    suspend fun resolve(providerId: String, type: ProviderType, modelId: String): ModelMetadata =
        withContext(Dispatchers.IO) {
            val catalog = loadCatalog()
            val auto = findMetadata(catalog, type, modelId) ?: default(type, modelId)
            mergeCustom(providerId, modelId, auto)
        }

    suspend fun resolveAll(providerId: String, type: ProviderType, modelIds: List<String>): Map<String, ModelMetadata> =
        withContext(Dispatchers.IO) {
            val catalog = loadCatalog()
            modelIds.associateWith { modelId ->
                val auto = findMetadata(catalog, type, modelId) ?: default(type, modelId)
                mergeCustom(providerId, modelId, auto)
            }
        }

    /** 自定义元数据优先于自动解析（拉取/内置）结果；providerId 为空（未关联配置）时跳过合并。 */
    private suspend fun mergeCustom(providerId: String, modelId: String, auto: ModelMetadata): ModelMetadata {
        if (providerId.isBlank()) return auto
        val custom = customModelMetadataStore.get(providerId, modelId)
        return mergeModelMetadata(modelId, auto, custom)
    }

    /** 启动时统一调用：经统一拉取器从本仓库刷新模型目录（12h 缓存，失败静默）。 */
    suspend fun refreshFromNetworkIfStale() {
        if (refreshAttemptedThisProcess) return
        refreshAttemptedThisProcess = true
        withContext(Dispatchers.IO) {
            // maxAgeMs = 0：跳过默认 12 小时保鲜期，每次启动条件请求对账，让清单改动立即生效。
            val result = runCatching { repoFetcher.fetch(MODELS_REPO_PATH, maxAgeMs = 0L) }.getOrNull()
            val body = when (result) {
                is RepoDataFetcher.FetchResult.Success -> result.content
                is RepoDataFetcher.FetchResult.FallbackDiskCache -> result.content
                else -> null
            } ?: return@withContext
            if (body.isBlank()) return@withContext
            runCatching { parseCatalog(json.parseToJsonElement(body)) }
                .onSuccess { cached = it }
                .onFailure { FileLogger.w(TAG, "解析模型元数据失败", it) }
        }
    }

    /** 纯只读链路：内存 → 本仓库磁盘缓存 → 内置 assets → 空目录（由调用方回退默认值），绝不发网络请求。 */
    private fun loadCatalog(): Catalog {
        cached?.let { return it }

        repoFetcher.readLocalCache(MODELS_REPO_PATH)?.let { body ->
            runCatching { parseCatalog(json.parseToJsonElement(body)) }.getOrNull()?.let {
                cached = it
                return it
            }
        }

        loadCatalogFromAssets()?.let {
            cached = it
            return it
        }

        return Catalog(emptyMap(), emptyMap())
    }

    private fun loadCatalogFromAssets(): Catalog? = runCatching {
        val body = context.assets.open(ASSET_FILE_NAME).bufferedReader().use { it.readText() }
        parseCatalog(json.parseToJsonElement(body))
    }.getOrNull()

    /** 目录中匹配不到模型时的兜底：统一视为文本模型，128k 输入 / 64k 输出。 */
    private fun default(type: ProviderType, modelId: String): ModelMetadata = ModelMetadata(
        id = modelId,
        providerId = type.name.lowercase(),
        displayName = modelId,
        contextTokens = DEFAULT_CONTEXT_TOKENS,
        inputTokens = DEFAULT_CONTEXT_TOKENS,
        outputTokens = DEFAULT_OUTPUT_TOKENS,
        supportsTools = true,
        supportsVision = false,
        supportsReasoning = false,
        source = ModelMetadata.Source.INFERRED
    )

    companion object {
        /** 从 models.dev 的 reasoning_options 数组中提取 effort 类型的档位 values；无 effort 档位时返回空列表。 */
        fun parseReasoningOptions(reasoningOptions: JsonElement?): List<String> =
            reasoningOptions?.takeIf { it !is JsonNull }?.jsonArray
                ?.mapNotNull { it.jsonObject }
                ?.firstOrNull { it["type"]?.jsonPrimitive?.content == "effort" }
                ?.get("values")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.content }
                .orEmpty()

        /**
         * 解析后的模型目录：[byProvider] 保留目录里的原始 id 大小写；[lowerByProvider] 结构相同但键统一小写
         * （复用同一批 [ModelMetadata] 实例），用于大小写不敏感匹配。
         */
        internal data class Catalog(
            val byProvider: Map<String, Map<String, ModelMetadata>>,
            val lowerByProvider: Map<String, Map<String, ModelMetadata>>
        )

        internal fun parseCatalog(root: JsonElement): Catalog {
            val byProvider = root.jsonObject.mapValues { (providerId, providerEl) ->
                val models = providerEl.jsonObject["models"]?.jsonObject.orEmpty()
                models.mapValues { (_, modelEl) ->
                    val model = modelEl.jsonObject
                    val limit = model["limit"]?.jsonObject
                    val modalities = model["modalities"]?.jsonObject
                    val inputModalities = modalities?.get("input")?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.content }
                        .orEmpty()
                    val outputModalities = modalities?.get("output")?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.content }
                        .orEmpty()
                    val cost = model["cost"]?.jsonObject
                    val reasoningOptions = parseReasoningOptions(model["reasoning_options"])
                    ModelMetadata(
                        id = model["id"]?.jsonPrimitive?.content ?: "",
                        providerId = providerId,
                        displayName = model["name"]?.jsonPrimitive?.content ?: model["id"]?.jsonPrimitive?.content.orEmpty(),
                        contextTokens = limit?.get("context")?.jsonPrimitive?.intOrNull ?: 0,
                        inputTokens = limit?.get("input")?.jsonPrimitive?.intOrNull,
                        outputTokens = limit?.get("output")?.jsonPrimitive?.intOrNull,
                        supportsTools = model["tool_call"]?.jsonPrimitive?.booleanOrNull == true,
                        supportsVision = "image" in inputModalities || "video" in inputModalities || "pdf" in inputModalities,
                        // 图像输出能力：models.dev 的 modalities.output 标注，或 Nano Banana 系 id 后缀兜底
                        // （内置快照缺 output 模态时也能识别 gemini-*-image 模型）。
                        supportsImageOutput = "image" in outputModalities ||
                            (model["id"]?.jsonPrimitive?.content.orEmpty().endsWith("-image")),
                        supportsReasoning = model["reasoning"]?.jsonPrimitive?.booleanOrNull == true,
                        supportsCustomTemperature = model["temperature"]?.jsonPrimitive?.booleanOrNull == true,
                        reasoningEffortOptions = reasoningOptions.takeIf { it.isNotEmpty() },
                        inputCostUsdPerM = cost?.get("input")?.jsonPrimitive?.doubleOrNull,
                        outputCostUsdPerM = cost?.get("output")?.jsonPrimitive?.doubleOrNull,
                        cacheReadCostUsdPerM = cost?.get("cache_read")?.jsonPrimitive?.doubleOrNull,
                        cacheWriteCostUsdPerM = cost?.get("cache_write")?.jsonPrimitive?.doubleOrNull,
                        source = ModelMetadata.Source.MODELS_DEV
                    )
                }
            }
            return Catalog(
                byProvider = byProvider,
                lowerByProvider = byProvider.mapValues { (_, models) ->
                    models.mapKeys { (id, _) -> id.lowercase() }
                }
            )
        }

        /**
         * 模型 id 匹配：每轮完整名称（精确 → 忽略大小写）优先于已知后缀候选，再逐级剥离 vendor 前缀重试；
         * 所有候选落空后才去掉最后一个连字符后缀一次，每次查找都先偏好 provider，再退到全目录。
         */
        internal fun findMetadata(
            catalog: Catalog,
            type: ProviderType,
            modelId: String
        ): ModelMetadata? {
            val preferred = preferredProviders(type)
            val normalized = if (modelId.startsWith(MODELS_PREFIX, ignoreCase = true)) {
                modelId.substring(MODELS_PREFIX.length)
            } else {
                modelId
            }
            val names = listOf(normalized) + vendorStrippedCandidates(normalized)
            for (name in names) {
                lookup(catalog.byProvider, preferred, listOf(name))?.let { return it }
                lookup(catalog.lowerByProvider, preferred, listOf(name.lowercase()))?.let { return it }
                lookup(catalog.byProvider, preferred, strippedCandidates(name).drop(1))?.let { return it }
                lookup(catalog.lowerByProvider, preferred, strippedCandidates(name.lowercase()).drop(1))?.let { return it }
            }
            for (name in names) {
                val lastDash = name.lastIndexOf('-')
                if (lastDash <= name.lastIndexOf('/') + 1) continue
                val candidate = name.substring(0, lastDash)
                lookup(catalog.byProvider, preferred, listOf(candidate))?.let { return it }
                lookup(catalog.lowerByProvider, preferred, listOf(candidate.lowercase()))?.let { return it }
            }
            return null
        }

        private fun preferredProviders(type: ProviderType): List<String> = when (type) {
            ProviderType.OPENAI -> listOf(
                "openai", "openrouter", "deepseek", "groq", "xai", "mistral",
                "togetherai", "alibaba", "moonshot", "github-copilot"
            )
            ProviderType.ANTHROPIC -> listOf("anthropic", "google-vertex-anthropic")
            ProviderType.GEMINI -> listOf("google", "google-vertex")
        }

        /** 在 [catalog]（键大小写已由调用方对齐）里按候选顺序查找：先偏好 provider，再全目录同名键。 */
        private fun lookup(
            catalog: Map<String, Map<String, ModelMetadata>>,
            preferred: List<String>,
            candidates: List<String>
        ): ModelMetadata? {
            for (candidate in candidates) {
                for (provider in preferred) {
                    catalog[provider]?.get(candidate)?.let { return it }
                }
                catalog.values.firstNotNullOfOrNull { models -> models[candidate] }?.let { return it }
            }
            return null
        }

        /**
         * 逐级剥离 vendor 前缀后的候选（`z-ai/glm-5.3-flash` → `glm-5.3-flash`，
         * `@cf/zai-org/glm-5.3-flash` → `zai-org/glm-5.3-flash` → `glm-5.3-flash`）。
         */
        private fun vendorStrippedCandidates(modelId: String): List<String> {
            val candidates = mutableListOf<String>()
            var current = modelId
            while (true) {
                val slash = current.indexOf('/')
                if (slash < 0 || slash == current.lastIndex) break
                current = current.substring(slash + 1)
                candidates.add(current)
            }
            return candidates
        }

        /** 生成匹配候选：原始 id 在前，随后迭代剥离常见后缀（-thinking/-preview/-high/-low 及括号形式），可连续剥离多层。 */
        private fun strippedCandidates(modelId: String): List<String> {
            val candidates = mutableListOf(modelId)
            var current = modelId
            var changed: Boolean
            do {
                changed = false
                for (suffix in MODEL_SUFFIXES) {
                    if (current.length > suffix.length && current.endsWith(suffix)) {
                        current = current.dropLast(suffix.length)
                        candidates.add(current)
                        changed = true
                        break
                    }
                }
            } while (changed)
            return candidates
        }

        const val TAG = "ModelMetadataService"
        const val ASSET_FILE_NAME = "api.official.json"

        /** 统一拉取器中的仓库相对路径：本仓库维护的 models.dev 全量快照（CI 每日同步）。 */
        const val MODELS_REPO_PATH = "data/models.json"
        const val DEFAULT_CONTEXT_TOKENS = 128_000
        const val DEFAULT_OUTPUT_TOKENS = 64_000

        /** Gemini 风格的模型 id 前缀（`models/gemini-2.5-pro`），匹配前剥离，忽略大小写。 */
        private const val MODELS_PREFIX = "models/"

        /** 兜底模糊匹配：依次尝试剥离的模型 id 后缀。 */
        private val MODEL_SUFFIXES = listOf(
            "-thinking", "-preview", "-high", "-low",
            "(thinking)", "(xhigh)", "(high)", "(low)"
        )
    }
}