package com.aharou.feature.settings.data.remote

import android.content.Context
import com.aharou.core.net.RepoDataFetcher
import com.aharou.core.util.FileLogger
import com.aharou.feature.settings.data.catalog.ModelCatalogCache
import com.aharou.feature.settings.data.catalog.ModelCatalogClient
import com.aharou.feature.settings.data.local.CustomModelMetadataStore
import com.aharou.feature.settings.domain.model.ModelMetadata
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.model.mergeModelMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelMetadataService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val customModelMetadataStore: CustomModelMetadataStore,
    private val modelCatalogCache: ModelCatalogCache,
    private val modelCatalogClient: ModelCatalogClient
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val activeCatalogParses = java.util.concurrent.atomic.AtomicInteger(0)

    /** 统一拉取器；模型元数据每日由 CI 发到独立的 data 分支，main 上不再存机器产物。 */
    private val repoFetcher = RepoDataFetcher(context, branch = "data")

    @Volatile
    private var cached: Catalog? = null

    /** 串行化目录加载：并发的 resolve 会同时穿透 [cached] 未命中的空窗，各自把目录读盘并解析一遍。 */
    private val catalogLock = Mutex()

    @Volatile
    private var refreshAttemptedThisProcess = false

    /** 运行时目录的后台刷新协程：不能挂在调用方作用域上（首次 resolveAll 返回后仍要跑完）。 */
    private val catalogRefreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtimeCatalogRefreshTriggered = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun resolve(providerId: String, type: ProviderType, modelId: String): ModelMetadata =
        withContext(Dispatchers.IO) {
            val catalog = loadCatalog()
            val auto = findMetadata(catalog, type, modelId) ?: default(type, modelId)
            mergeCustom(providerId, modelId, auto)
        }

    suspend fun resolveAll(providerId: String, type: ProviderType, modelIds: List<String>): Map<String, ModelMetadata> =
        withContext(Dispatchers.IO) {
            // 懒触发：首次进设置页照旧用现有数据，后台刷新运行时目录，下一次 resolveAll 生效。
            triggerRuntimeCatalogRefresh()
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
            val fetchStartedNs = System.nanoTime()
            FileLogger.memoryCheckpoint(TAG, "catalog.fetch.start", details = "operation=$fetchStartedNs")
            // maxAgeMs = 0：跳过默认 12 小时保鲜期，每次启动条件请求对账，让清单改动立即生效。
            val result = runCatching { repoFetcher.fetch(MODELS_REPO_PATH, maxAgeMs = 0L) }.getOrNull()
            FileLogger.memoryCheckpoint(
                TAG,
                "catalog.fetch.finished",
                elapsedMs = (System.nanoTime() - fetchStartedNs) / 1_000_000,
                details = "operation=$fetchStartedNs hasContent=" +
                    (result is RepoDataFetcher.FetchResult.Success ||
                        result is RepoDataFetcher.FetchResult.FallbackDiskCache)
            )
            val body = when (result) {
                is RepoDataFetcher.FetchResult.Success -> result.content
                is RepoDataFetcher.FetchResult.FallbackDiskCache -> result.content
                else -> null
            } ?: return@withContext
            if (body.isBlank()) return@withContext
            val parsed = runCatching { parseCatalogWithDiagnostics(body, "refresh") }
                .onFailure { FileLogger.w(TAG, "解析模型元数据失败", it) }
                .getOrNull() ?: return@withContext
            catalogLock.withLock { cached = parsed }
        }
    }

    /**
     * 纯只读链路：内存 → 运行时目录缓存（未过期）→ 本仓库磁盘缓存 → 内置 assets → 空目录
     * （由调用方回退默认值），绝不发网络请求。
     */
    private suspend fun loadCatalog(): Catalog {
        cached?.let { return it }
        return catalogLock.withLock {
            cached?.let { return it }

            loadCatalogFromRuntimeCache()?.let {
                cached = it
                return it
            }

            val readStartedNs = System.nanoTime()
            FileLogger.memoryCheckpoint(TAG, "catalog.disk.start", details = "operation=$readStartedNs")
            val diskBody = repoFetcher.readLocalCache(MODELS_REPO_PATH)
            FileLogger.memoryCheckpoint(
                TAG,
                "catalog.disk.finished",
                elapsedMs = (System.nanoTime() - readStartedNs) / 1_000_000,
                details = "operation=$readStartedNs chars=${diskBody?.length ?: 0} present=${diskBody != null}"
            )
            diskBody?.let { body ->
                runCatching { parseCatalogWithDiagnostics(body, "disk") }.getOrNull()?.let {
                    cached = it
                    return it
                }
            }

            loadCatalogFromAssets()?.let {
                cached = it
                return it
            }

            Catalog(emptyMap(), emptyMap())
        }
    }

    /** 运行时目录缓存（models.dev 直连，落盘见 [ModelCatalogCache]）：只在未过期时使用，过期即回退。 */
    private fun loadCatalogFromRuntimeCache(): Catalog? {
        val snapshot = modelCatalogCache.readFresh(CATALOG_CACHE_MAX_AGE_MS) ?: return null
        return runCatching { parseCatalogWithDiagnostics(snapshot.body, "runtime") }
            .onFailure { FileLogger.w(TAG, "解析运行时模型目录失败", it) }
            .getOrNull()
    }

    /**
     * 手动刷新运行时模型目录（供 UI 之后接）：成功时同时更新内存与磁盘缓存并返回 true，
     * 失败返回 false，调用方继续用现有数据。
     */
    suspend fun refreshCatalog(): Boolean = withContext(Dispatchers.IO) {
        val snapshot = modelCatalogClient.fetch() ?: return@withContext false
        val parsed = runCatching { parseCatalogWithDiagnostics(snapshot.body, "runtime-refresh") }
            .onFailure { FileLogger.w(TAG, "解析运行时模型目录失败", it) }
            .getOrNull() ?: return@withContext false
        // 空目录宁可继续用旧数据，也不要拿它覆盖内存快照（会让所有模型回退兜底值）。
        if (parsed.byProvider.values.none { it.isNotEmpty() }) return@withContext false
        catalogLock.withLock { cached = parsed }
        true
    }

    /** 每个进程只懒触发一次运行时目录刷新；失败静默（下次 resolveAll 仍用现有数据）。 */
    private fun triggerRuntimeCatalogRefresh() {
        if (!runtimeCatalogRefreshTriggered.compareAndSet(false, true)) return
        catalogRefreshScope.launch {
            runCatching { refreshCatalog() }
                .onFailure { FileLogger.w(TAG, "运行时模型目录后台刷新失败", it) }
        }
    }

    private fun loadCatalogFromAssets(): Catalog? = runCatching {
        val readStartedNs = System.nanoTime()
        FileLogger.memoryCheckpoint(TAG, "catalog.assets.start", details = "operation=$readStartedNs")
        val body = context.assets.open(ASSET_FILE_NAME).bufferedReader().use { it.readText() }
        FileLogger.memoryCheckpoint(
            TAG,
            "catalog.assets.ready",
            elapsedMs = (System.nanoTime() - readStartedNs) / 1_000_000,
            details = "operation=$readStartedNs chars=${body.length}"
        )
        parseCatalogWithDiagnostics(body, "assets")
    }.getOrNull()

    /** 解析同时记录阶段与并发解析数：并发穿透 [cached] 空窗时能看出重复解析。 */
    private fun parseCatalogWithDiagnostics(body: String, source: String): Catalog {
        val startedNs = System.nanoTime()
        val concurrent = activeCatalogParses.incrementAndGet()
        try {
            FileLogger.memoryCheckpoint(
                TAG,
                "catalog.parse.start",
                details = "operation=$startedNs source=$source chars=${body.length} activeParses=$concurrent"
            )
            val root = json.parseToJsonElement(body)
            FileLogger.memoryCheckpoint(
                TAG,
                "catalog.json.ready",
                elapsedMs = (System.nanoTime() - startedNs) / 1_000_000,
                details = "operation=$startedNs source=$source activeParses=${activeCatalogParses.get()}"
            )
            val catalog = parseCatalog(root)
            FileLogger.memoryCheckpoint(
                TAG,
                "catalog.parse.ready",
                elapsedMs = (System.nanoTime() - startedNs) / 1_000_000,
                details = "operation=$startedNs source=$source providers=${catalog.byProvider.size} " +
                    "models=${catalog.byProvider.values.sumOf { it.size }} activeParses=${activeCatalogParses.get()}"
            )
            return catalog
        } finally {
            activeCatalogParses.decrementAndGet()
        }
    }

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
        /**
         * models.dev `reasoning_options` 的完整解析结果。
         * 一个模型可以同时声明多种形态（例如 toggle + effort、effort + budget_tokens），故逐项收集而非只取第一个命中项。
         */
        internal data class ReasoningOptions(
            val effortValues: List<String> = emptyList(),
            val supportsToggle: Boolean = false,
            val hasBudgetTokens: Boolean = false,
            val budgetMin: Int? = null,
            val budgetMax: Int? = null
        )

        /** 从 models.dev 的 reasoning_options 数组中提取 effort 类型的档位 values；无 effort 档位时返回空列表。 */
        fun parseReasoningOptions(reasoningOptions: JsonElement?): List<String> =
            parseReasoningOptionsFull(reasoningOptions).effortValues

        /**
         * 同 [parseReasoningOptions]，但同时识别 toggle（只有思考开/关）与 budget_tokens（按 token 预算控制深度）两种形态。
         * 两者目前只做展示用途，选择器仍全列档位——models.dev 的声明常缺档，按它过滤会让用户选不到中间档。
         */
        internal fun parseReasoningOptionsFull(reasoningOptions: JsonElement?): ReasoningOptions {
            val options = reasoningOptions?.takeIf { it !is JsonNull }?.jsonArray
                ?.mapNotNull { el -> el as? JsonObject }
                .orEmpty()
            val typeOf: (JsonObject) -> String? = { o -> o["type"]?.jsonPrimitive?.content }
            val effort = options.firstOrNull { typeOf(it) == "effort" }
            val budget = options.firstOrNull { typeOf(it) == "budget_tokens" }
            return ReasoningOptions(
                effortValues = effort?.get("values")?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.content }
                    .orEmpty(),
                supportsToggle = options.any { typeOf(it) == "toggle" },
                hasBudgetTokens = budget != null,
                budgetMin = budget?.get("min")?.jsonPrimitive?.intOrNull,
                budgetMax = budget?.get("max")?.jsonPrimitive?.intOrNull
            )
        }

        /**
         * 解析后的模型目录：[byProvider] 保留目录里的原始 id 大小写；[lowerByProvider] 结构相同但键统一小写
         * （复用同一批 [ModelMetadata] 实例），用于大小写不敏感匹配。
         */
        internal data class Catalog(
            val byProvider: Map<String, Map<String, ModelMetadata>>,
            val lowerByProvider: Map<String, Map<String, ModelMetadata>>
        )

        /**
         * 宽松解析：provider 层结构非法直接跳过；单个模型条目解析失败只跳过该条，
         * 不让运行时目录里的一条脏数据废掉整份目录。
         */
        internal fun parseCatalog(root: JsonElement): Catalog {
            val rootObj = root as? JsonObject ?: return Catalog(emptyMap(), emptyMap())
            val byProvider = rootObj.mapNotNull { (providerId, providerEl) ->
                val providerObj = providerEl as? JsonObject ?: return@mapNotNull null
                val models = providerObj["models"] as? JsonObject ?: JsonObject(emptyMap())
                val parsedModels = models.mapNotNull { (modelKey, modelEl) ->
                    parseModel(providerId, modelEl)?.let { modelKey to it }
                }.toMap()
                providerId to parsedModels
            }.toMap()
            return Catalog(
                byProvider = byProvider,
                lowerByProvider = byProvider.mapValues { (_, models) ->
                    models.mapKeys { (id, _) -> id.lowercase() }
                }
            )
        }

        /** 单条模型：字段缺失走默认 / null，字段类型不对则整条跳过（见 [parseCatalog]）。 */
        private fun parseModel(providerId: String, modelEl: JsonElement): ModelMetadata? {
            val model = modelEl as? JsonObject ?: return null
            return runCatching {
                val limit = model["limit"] as? JsonObject
                val modalities = model["modalities"] as? JsonObject
                val inputModalities = (modalities?.get("input") as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    .orEmpty()
                val outputModalities = (modalities?.get("output") as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                    .orEmpty()
                val cost = model["cost"] as? JsonObject
                val reasoningOptions = parseReasoningOptionsFull(model["reasoning_options"])
                val modelId = (model["id"] as? JsonPrimitive)?.content ?: ""
                ModelMetadata(
                    id = modelId,
                    providerId = providerId,
                    displayName = (model["name"] as? JsonPrimitive)?.content ?: modelId,
                    contextTokens = (limit?.get("context") as? JsonPrimitive)?.intOrNull ?: 0,
                    inputTokens = (limit?.get("input") as? JsonPrimitive)?.intOrNull,
                    outputTokens = (limit?.get("output") as? JsonPrimitive)?.intOrNull,
                    supportsTools = (model["tool_call"] as? JsonPrimitive)?.booleanOrNull == true,
                    supportsVision = "image" in inputModalities || "video" in inputModalities || "pdf" in inputModalities,
                    // 图像输出能力：models.dev 的 modalities.output 标注，或 Nano Banana 系 id 后缀兜底
                    // （内置快照缺 output 模态时也能识别 gemini-*-image 模型）。
                    supportsImageOutput = "image" in outputModalities || modelId.endsWith("-image"),
                    supportsReasoning = (model["reasoning"] as? JsonPrimitive)?.booleanOrNull == true,
                    supportsCustomTemperature = (model["temperature"] as? JsonPrimitive)?.booleanOrNull == true,
                    reasoningEffortOptions = reasoningOptions.effortValues.takeIf { it.isNotEmpty() },
                    reasoningSupportsToggle = reasoningOptions.supportsToggle,
                    reasoningBudgetTokens = reasoningOptions.hasBudgetTokens,
                    reasoningBudgetTokensMin = reasoningOptions.budgetMin,
                    reasoningBudgetTokensMax = reasoningOptions.budgetMax,
                    inputCostUsdPerM = (cost?.get("input") as? JsonPrimitive)?.doubleOrNull,
                    outputCostUsdPerM = (cost?.get("output") as? JsonPrimitive)?.doubleOrNull,
                    cacheReadCostUsdPerM = (cost?.get("cache_read") as? JsonPrimitive)?.doubleOrNull,
                    cacheWriteCostUsdPerM = (cost?.get("cache_write") as? JsonPrimitive)?.doubleOrNull,
                    source = ModelMetadata.Source.MODELS_DEV
                )
            }.onFailure {
                FileLogger.d(TAG, "跳过无法解析的模型条目 provider=$providerId")
            }.getOrNull()
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
            val names = listOf(normalized) + listOfNotNull(stripBracketPrefixes(normalized)) +
                vendorStrippedCandidates(normalized)
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
         * 剥离开头的 `[...]` 计费 / 分组前缀。
         *
         * 中转站常把计价与分组拼进模型名（`[0.18/次][按次K6] claude-opus-5-5`），
         * 这类名字在目录里必然查不到，会把窗口解析一路拖到兜底值。多试一次剥完前缀的名字。
         */
        private fun stripBracketPrefixes(modelId: String): String? {
            var current = modelId.trim()
            var stripped = false
            while (current.startsWith("[")) {
                val end = current.indexOf(']')
                if (end <= 0) break
                current = current.substring(end + 1).trimStart()
                stripped = true
            }
            return current.takeIf { stripped && it.isNotEmpty() }
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

        /** 运行时模型目录缓存的保鲜期：超过即回退内置快照，后台刷新成功后下次生效。 */
        const val CATALOG_CACHE_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        /** Gemini 风格的模型 id 前缀（`models/gemini-2.5-pro`），匹配前剥离，忽略大小写。 */
        private const val MODELS_PREFIX = "models/"

        /** 兜底模糊匹配：依次尝试剥离的模型 id 后缀。 */
        private val MODEL_SUFFIXES = listOf(
            "-thinking", "-preview", "-high", "-low",
            "(thinking)", "(xhigh)", "(high)", "(low)"
        )
    }
}