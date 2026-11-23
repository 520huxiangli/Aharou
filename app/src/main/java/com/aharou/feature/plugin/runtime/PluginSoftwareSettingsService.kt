package com.aharou.feature.plugin.runtime

import android.content.Context
import com.aharou.feature.plugin.domain.PluginHostException
import com.aharou.feature.plugin.domain.PluginManager
import com.aharou.feature.settings.data.remote.FetchModelsException
import com.aharou.feature.settings.data.remote.ModelApiService
import com.aharou.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aharou.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aharou.feature.settings.data.repository.ImageGenModelSettingsRepository
import com.aharou.feature.settings.data.repository.TitleModelSettingsRepository
import com.aharou.feature.settings.data.repository.VisionModelSettingsRepository
import com.aharou.feature.settings.data.repository.VoiceSttSettingsRepository
import com.aharou.feature.settings.data.repository.VoiceTtsSettingsRepository
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import com.aharou.feature.voice.playback.PlaybackSession
import com.aharou.feature.voice.playback.SpeechPlayback
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 朗读链路的取用入口：`SpeechPlayback` 是语音模块内的 internal 类，
 * 构造注入会把它带进本类的公开签名（Kotlin 不允许），所以经 EntryPoint 取一次。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PluginSpeechPlaybackEntryPoint {
    fun speechPlayback(): SpeechPlayback
}

/**
 * `softwareSettings.*` 宿主方法，对应 Operit 的 `Tools.SoftwareSettings`（`JsTools.kt`）。
 *
 * 映射原则：能落到 Aharou 既有能力的就真写进去，落不进去的一律 `UNSUPPORTED` 并写清原因，
 * 绝不返回一个「看起来成功了」的影子结果。
 *
 * | Operit 方法 | Aharou 落点 |
 * | --- | --- |
 * | listModelConfigs / createModelConfig / updateModelConfig / deleteModelConfig | `AIProviderRepository`（设置页里那份真实供应商列表） |
 * | testModelConfigConnection | `ModelApiService.fetchModels`（与设置页「拉取模型」同一条请求） |
 * | listFunctionModelConfigs / getFunctionModelConfig / setFunctionModelConfig | 各角色模型仓库（默认/标题/压缩/识图/语音识别/生图） |
 * | getSpeechServicesConfig / setSpeechServicesConfig | `VoiceTtsSettingsRepository` + `VoiceSttSettingsRepository` |
 * | testTtsPlayback | `SpeechPlayback`（对话朗读用的同一条合成与播放链路） |
 * | listSandboxPackages / setSandboxPackageEnabled / executeSandboxScriptDirect | 无对应能力：Aharou 没有 Operit 的 QuickJS 沙盒包 |
 * | restartMcpWithLogs | 无对应能力：MCP 由会话层惰性连接，没有常驻进程可重启 |
 */
@Singleton
class PluginSoftwareSettingsService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pluginManager: PluginManager,
    private val providers: AIProviderRepository,
    private val modelApi: ModelApiService,
    private val ttsSettings: VoiceTtsSettingsRepository,
    private val sttSettings: VoiceSttSettingsRepository,
    private val defaultModel: DefaultModelSettingsRepository,
    private val titleModel: TitleModelSettingsRepository,
    private val compactionModel: CompactionModelSettingsRepository,
    private val visionModel: VisionModelSettingsRepository,
    private val imageGenModel: ImageGenModelSettingsRepository,
) {

    /** 试听独占一份播报会话：不和聊天里的自动朗读抢播放器，也不会把正在念的回复掐断。 */
    private val previewPlayback: PlaybackSession by lazy {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            PluginSpeechPlaybackEntryPoint::class.java
        ).speechPlayback().newSession()
    }

    // ---------------- 模型配置 ----------------

    suspend fun listModelConfigs(pluginId: String, params: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            ensureActive(pluginId)
            val configs = providers.getAllProviders().first()
            buildJsonObject {
                put("totalConfigCount", configs.size)
                // Operit 有一个固定的默认配置 id；Aharou 的供应商都是平级的，没有这个概念
                put("defaultConfigId", "")
                put("configs", buildJsonArray { configs.forEach { add(providerItem(it)) } })
                put("functionMappings", functionMappingsJson())
            }
        }

    suspend fun createModelConfig(pluginId: String, params: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            ensureActive(pluginId)
            val name = params.text("name")?.trim().takeUnless { it.isNullOrEmpty() } ?: "New Model Config"
            val baseUrl = firstText(params, "api_endpoint", "apiEndpoint", "base_url", "baseUrl")
                ?: throw PluginHostException("PARAM_INVALID", "createModelConfig 需要 api_endpoint（供应商地址）")
            val type = parseProviderType(firstText(params, "api_provider_type", "apiProviderType", "type"))
                ?: if (firstText(params, "api_provider_type", "apiProviderType", "type").isNullOrBlank()) {
                    ProviderType.OPENAI
                } else {
                    throw PluginHostException("PARAM_INVALID", "api_provider_type 取值无效，只支持 OPENAI / ANTHROPIC / GEMINI")
                }
            val apiKey = firstText(params, "api_key", "apiKey").orEmpty()
            val modelName = firstText(params, "model_name", "modelName", "default_model").orEmpty().trim()
            val declared = params.stringList("models") ?: params.stringList("model_list").orEmpty()
            val models = (if (modelName.isNotBlank()) listOf(modelName) else emptyList())
                .plus(declared)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()

            val provider = AIProviderConfig(
                id = UUID.randomUUID().toString(),
                name = name,
                type = type,
                apiKey = apiKey,
                baseUrl = baseUrl.trim(),
                defaultModel = modelName,
                models = models,
                selectedModel = modelName,
                isEnabled = params.bool("enabled") ?: true,
                customHeaders = params.headerMap().orEmpty(),
            )
            providers.saveProvider(provider)
            buildJsonObject {
                put("created", true)
                put("config", providerItem(provider))
            }
        }

    suspend fun updateModelConfig(pluginId: String, params: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            ensureActive(pluginId)
            val id = params.requireConfigId()
            val current = providers.getProviderById(id) ?: throw configNotFound(id)
            var next = current
            val changed = mutableListOf<String>()

            params.text("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
                next = next.copy(name = value)
                changed += "name"
            }
            firstText(params, "api_endpoint", "apiEndpoint", "base_url", "baseUrl")?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { value ->
                    next = next.copy(baseUrl = value)
                    changed += "apiEndpoint"
                }
            firstText(params, "api_key", "apiKey")?.let { value ->
                next = next.copy(apiKey = value)
                changed += "apiKey"
            }
            val typeRaw = firstText(params, "api_provider_type", "apiProviderType", "type")
            if (!typeRaw.isNullOrBlank()) {
                val type = parseProviderType(typeRaw)
                    ?: throw PluginHostException("PARAM_INVALID", "只支持 OPENAI / ANTHROPIC / GEMINI")
                next = next.copy(type = type)
                changed += "apiProviderType"
            }
            firstText(params, "model_name", "modelName", "default_model")?.trim()
                ?.takeIf { it.isNotEmpty() }?.let { value ->
                    next = next.copy(defaultModel = value, selectedModel = value)
                    if (value !in next.models) next = next.copy(models = next.models + value)
                    changed += "modelName"
                }
            (params.stringList("models") ?: params.stringList("model_list"))?.let { list ->
                next = next.copy(models = list)
                if (next.effectiveModel !in list && list.isNotEmpty()) {
                    next = next.copy(selectedModel = list.first())
                }
                changed += "models"
            }
            params.bool("enabled")?.let { enabled ->
                next = next.copy(isEnabled = enabled)
                changed += "enabled"
            }
            params.headerMap()?.let { headers ->
                next = next.copy(customHeaders = headers)
                changed += "customHeaders"
            }
            if (changed.isEmpty()) {
                throw PluginHostException("PARAM_INVALID", "至少提供一个要更新的字段")
            }
            providers.saveProvider(next)
            buildJsonObject {
                put("updated", true)
                put("config", providerItem(next))
                put("changedFields", stringArray(changed.distinct()))
            }
        }

    suspend fun deleteModelConfig(pluginId: String, params: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            ensureActive(pluginId)
            val id = params.requireConfigId()
            if (providers.getProviderById(id) == null) throw configNotFound(id)
            providers.deleteProvider(id)
            buildJsonObject {
                put("deleted", true)
                put("configId", id)
            }
        }

    suspend fun testModelConfigConnection(pluginId: String, params: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            ensureActive(pluginId)
            val id = params.requireConfigId()
            val provider = providers.getProviderById(id) ?: throw configNotFound(id)
            val requestedIndex = params.int("model_index")?.coerceAtLeast(0) ?: 0
            val testedModel = provider.models.getOrNull(requestedIndex) ?: provider.effectiveModel
            val outcome = modelApi.fetchModels(
                baseUrl = provider.baseUrlForKey(provider.firstUsableApiKey),
                apiKey = provider.firstUsableApiKey,
                type = provider.type,
                useFullUrl = provider.useFullUrl,
                customHeaders = provider.customHeaders,
            )
            buildJsonObject {
                put("configId", provider.id)
                put("configName", provider.name)
                put("providerType", provider.type.name)
                put("requestedModelIndex", requestedIndex)
                put("testedModelName", testedModel)
                put(
                    "success",
                    outcome.fold(onSuccess = { true }, onFailure = { false })
                )
                outcome.fold(
                    onSuccess = { result ->
                        put("verified", true)
                        put("totalTests", 1)
                        put("passedTests", 1)
                        put("failedTests", 0)
                        put("latencyMs", result.debugInfo.latencyMs)
                        put("responseCode", result.debugInfo.responseCode)
                        put("modelCount", result.models.size)
                        put("models", stringArray(result.models.take(MAX_RETURNED_MODELS)))
                        put("tests", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "models")
                                put("success", true)
                                put("outcome", "passed")
                            })
                        })
                    },
                    onFailure = { error ->
                        val debug = (error as? FetchModelsException)?.debugInfo
                        put("verified", true)
                        put("totalTests", 1)
                        put("passedTests", 0)
                        put("failedTests", 1)
                        put("latencyMs", debug?.latencyMs ?: 0)
                        put("responseCode", debug?.responseCode ?: 0)
                        put("error", error.message ?: "连接测试失败")
                        put("tests", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "models")
                                put("success", false)
                                put("outcome", "failed")
                                put("error", error.message ?: "连接测试失败")
                            })
                        })
                    }
                )
            }
        }

    // ---------------- 功能模型绑定 ----------------

    suspend fun listFunctionModelConfigs(pluginId: String, params: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            ensureActive(pluginId)
            buildJsonObject {
                put("defaultConfigId", "")
                put("mappings", functionMappingsJson())
            }
        }

    suspend fun getFunctionModelConfig(pluginId: String, params: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            ensureActive(pluginId)
            val type = params.requireFunctionType()
            val role = supportedRoles.firstOrNull { it.type == type }
                ?: throw unsupportedFunctionType(type)
            val (providerId, model) = role.read()
            val provider = providerId.takeIf { it.isNotBlank() }?.let { providers.getProviderById(it) }
            buildJsonObject {
                put("functionType", type)
                put("configId", providerId)
                put("configName", provider?.name ?: "")
                // Aharou 的角色模型只存 (provider, model)，没有「同配置下第几号模型」的概念
                put("modelIndex", 0)
                put("selectedModel", model)
                put("supported", true)
            }
        }

    suspend fun setFunctionModelConfig(pluginId: String, params: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            ensureActive(pluginId)
            val type = params.requireFunctionType()
            val role = supportedRoles.firstOrNull { it.type == type }
                ?: throw unsupportedFunctionType(type)
            val configId = firstText(params, "config_id", "configId")
                ?: throw PluginHostException("PARAM_MISSING", "missing parameter: config_id")
            val provider = providers.getProviderById(configId) ?: throw configNotFound(configId)
            val requestedIndex = params.int("model_index")?.coerceAtLeast(0) ?: 0
            val actualIndex = if (provider.models.isEmpty()) 0 else requestedIndex.coerceIn(0, provider.models.size - 1)
            val model = provider.models.getOrNull(actualIndex) ?: provider.effectiveModel
            if (model.isBlank()) {
                throw PluginHostException("PARAM_INVALID", "供应商「${provider.name}」还没有可用模型，先给它设一个模型")
            }
            role.write(provider.id, model)
            buildJsonObject {
                put("functionType", type)
                put("configId", provider.id)
                put("configName", provider.name)
                put("requestedModelIndex", requestedIndex)
                put("actualModelIndex", actualIndex)
                put("selectedModel", model)
            }
        }

    // ---------------- 语音配置 ----------------

    suspend fun getSpeechServicesConfig(pluginId: String, params: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            ensureActive(pluginId)
            speechConfigJson()
        }

    suspend fun setSpeechServicesConfig(pluginId: String, params: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            ensureActive(pluginId)
            var ttsProviderId = ttsSettings.getTtsProviderId()
            var ttsModel = ttsSettings.getTtsModel()
            var ttsVoice = ttsSettings.getVoice()
            var userVoice = ttsSettings.getUserVoice()
            var sttProviderId = sttSettings.getSttProviderId()
            var sttModel = sttSettings.getSttModel()
            val applied = mutableListOf<String>()
            val ignored = mutableListOf<String>()

            // 先应用显式的供应商 id，再按 tts_service_type 判定：两参数同时给时以显式 id 为准。
            firstText(params, "tts_provider", "tts_provider_id")?.let { raw ->
                ttsProviderId = raw.trim()
                applied += "tts_provider"
            }

            val serviceType = params.text("tts_service_type")?.trim()?.uppercase()
            if (!serviceType.isNullOrEmpty()) {
                when (serviceType) {
                    "SYSTEM", "NONE", "DISABLED", "OFF" -> {
                        ttsProviderId = ""
                        applied += "tts_service_type"
                    }
                    "AHAROU", "AHAROU_CLOUD", "CLOUD", "HTTP", "VITS" -> {
                        applied += "tts_service_type"
                        if (ttsProviderId.isBlank()) {
                            throw PluginHostException(
                                "PARAM_INVALID",
                                "把 TTS 切到云端时需要 tts_provider（供应商 id，可用 listModelConfigs 查）"
                            )
                        }
                    }
                    else -> throw PluginHostException(
                        "PARAM_INVALID",
                        "tts_service_type 只支持 SYSTEM / AHAROU_CLOUD，收到：$serviceType"
                    )
                }
            }

            params.text("tts_model")?.let { value ->
                ttsModel = value.trim()
                applied += "tts_model"
            }
            firstText(params, "tts_voice", "tts_voice_id")?.let { value ->
                ttsVoice = value.trim()
                applied += "tts_voice"
            }
            firstText(params, "tts_voice_user", "tts_user_voice")?.let { value ->
                userVoice = value.trim()
                applied += "tts_voice_user"
            }
            firstText(params, "stt_provider", "stt_provider_id")?.let { value ->
                sttProviderId = value.trim()
                applied += "stt_provider"
            }
            params.text("stt_model")?.let { value ->
                sttModel = value.trim()
                applied += "stt_model"
            }
            params.bool("auto_read")?.let { enabled ->
                ttsSettings.setAutoReadAloud(enabled)
                applied += "auto_read"
            }
            params.bool("chat_mode")?.let { enabled ->
                ttsSettings.setChatVoiceMode(enabled)
                applied += "chat_mode"
            }

            // 供应商 id 必须真实存在，否则下次朗读会静默回落到系统引擎——那看起来像「设置没生效」。
            if (ttsProviderId.isNotBlank() && providers.getProviderById(ttsProviderId) == null) {
                throw configNotFound(ttsProviderId)
            }
            if (sttProviderId.isNotBlank() && providers.getProviderById(sttProviderId) == null) {
                throw configNotFound(sttProviderId)
            }

            ttsSettings.setTtsModel(ttsProviderId, ttsModel, ttsVoice)
            ttsSettings.setUserVoice(userVoice)
            sttSettings.setSttModel(sttProviderId, sttModel)

            knownSpeechKeys.forEach { key -> if (params.containsKey(key) && key !in applied) ignored += key }
            params.keys.filterNot { it in knownSpeechKeys }.forEach { ignored += it }

            speechConfigJson(
                extra = buildJsonObject {
                    put("applied", stringArray(applied.distinct()))
                    put("ignored", stringArray(ignored.distinct()))
                }
            )
        }

    suspend fun testTtsPlayback(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val text = params.text("text")
            ?: throw PluginHostException("PARAM_MISSING", "missing parameter: text")
        if (text.isBlank()) throw PluginHostException("PARAM_INVALID", "text 不能为空")
        if (text.length > MAX_TTS_TEST_CHARS) {
            throw PluginHostException("PARAM_INVALID", "text 超长（上限 $MAX_TTS_TEST_CHARS 字符）")
        }
        val interrupt = params.bool("interrupt") ?: true
        // 播放器必须在主线程创建；speakStreamed 内部自己切 IO 做合成
        val started = withContext(Dispatchers.Main) {
            previewPlayback.speakStreamed(text)
        }
        val engine = if (ttsSettings.getTtsProviderId().isBlank()) "system" else "cloud"
        return buildJsonObject {
            put("initialized", true)
            put("playbackTriggered", started)
            put("interrupt", interrupt)
            put("textLength", text.length)
            put("speechRate", 1.0)
            put("pitch", 1.0)
            put("engine", engine)
            if (!started) {
                put("message", "没能出声：云端合成失败且系统 TTS 也不可用，检查语音合成设置")
            }
        }
    }

    // ---------------- 无对应能力 ----------------

    suspend fun listSandboxPackages(pluginId: String, params: JsonObject): JsonElement =
        unsupported("listSandboxPackages", "Aharou 没有 Operit 的沙盒包（QuickJS 脚本包）体系。插件要执行脚本请走 host.bash / terminal.run，或在页面里直接跑 JS。")

    suspend fun setSandboxPackageEnabled(pluginId: String, params: JsonObject): JsonElement =
        unsupported("setSandboxPackageEnabled", "Aharou 没有沙盒包可启停。")

    suspend fun executeSandboxScriptDirect(pluginId: String, params: JsonObject): JsonElement =
        unsupported("executeSandboxScriptDirect", "Aharou 没有沙盒运行时。要跑脚本用 host.bash；要跑 Compose DSL 用 ui.render。")

    suspend fun restartMcpWithLogs(pluginId: String, params: JsonObject): JsonElement =
        unsupported("restartMcpWithLogs", "Aharou 的 MCP 由会话层按需连接，没有常驻进程可重启；用 mcp.status 查看连接与工具状态。")

    private fun unsupported(method: String, reason: String): Nothing =
        throw PluginHostException("UNSUPPORTED", "$method 不受支持：$reason")

    // ---------------- 内部 ----------------

    private suspend fun speechConfigJson(extra: JsonObject? = null): JsonObject {
        val ttsProviderId = ttsSettings.getTtsProviderId()
        val ttsModel = ttsSettings.getTtsModel()
        val ttsVoice = ttsSettings.getVoice()
        val userVoice = ttsSettings.getUserVoice()
        val autoRead = ttsSettings.isAutoReadAloud()
        val sttProviderId = sttSettings.getSttProviderId()
        val sttModel = sttSettings.getSttModel()
        val ttsProvider = ttsProviderId.takeIf { it.isNotBlank() }?.let { providers.getProviderById(it) }
        val sttProvider = sttProviderId.takeIf { it.isNotBlank() }?.let { providers.getProviderById(it) }
        return buildJsonObject {
            put("ttsServiceType", if (ttsProviderId.isBlank()) "SYSTEM" else "AHAROU_CLOUD")
            put("ttsProviderId", ttsProviderId)
            put("ttsHttpConfig", buildJsonObject {
                put("urlTemplate", ttsProvider?.baseUrl.orEmpty())
                put("apiKeySet", ttsProvider?.firstUsableApiKey?.isNotBlank() ?: false)
                put("apiKeyPreview", maskSecret(ttsProvider?.firstUsableApiKey.orEmpty()))
                put("headers", buildJsonObject {
                    ttsProvider?.customHeaders?.forEach { (name, value) -> put(name, value) }
                })
                put("httpMethod", "POST")
                put("requestBody", "")
                put("contentType", "application/json")
                put("localeTag", "")
                put("voiceId", ttsVoice)
                put("modelName", ttsModel)
                put("responsePipeline", buildJsonArray { })
            })
            put("ttsVitsPackageConfig", buildJsonObject {
                put("packagePath", "")
                put("speakerId", "")
                put("options", buildJsonObject { })
            })
            put("ttsCleanerRegexs", buildJsonArray { })
            // Aharou 没有语速/音高调节，恒为 1.0；真实取值能让比较逻辑不误判
            put("ttsSpeechRate", 1.0)
            put("ttsPitch", 1.0)
            put("ttsVoiceUser", userVoice)
            put("autoRead", autoRead)
            put("sttServiceType", if (sttProviderId.isBlank()) "OFFLINE" else "AHAROU_CLOUD")
            put("sttProviderId", sttProviderId)
            put("sttHttpConfig", buildJsonObject {
                put("endpointUrl", sttProvider?.baseUrl.orEmpty())
                put("apiKeySet", sttProvider?.firstUsableApiKey?.isNotBlank() ?: false)
                put("apiKeyPreview", maskSecret(sttProvider?.firstUsableApiKey.orEmpty()))
                put("modelName", sttModel)
            })
            put("unsupported", stringArray(
                listOf(
                    "ttsVitsPackageConfig：Aharou 用本机克隆音色，不接受 VITS 包路径/speakerId",
                    "ttsCleanerRegexs：没有可配置的朗读清洗正则",
                    "ttsSpeechRate / ttsPitch：不支持调节",
                )
            ))
            if (extra != null) extra.forEach { (key, value) -> put(key, value) }
        }
    }

    private suspend fun functionMappingsJson(): JsonArray = buildJsonArray {
        supportedRoles.forEach { role ->
            val (providerId, model) = role.read()
            val provider = providerId.takeIf { it.isNotBlank() }?.let { providers.getProviderById(it) }
            add(buildJsonObject {
                put("functionType", role.type)
                put("configId", providerId)
                put("configName", provider?.name ?: "")
                put("modelIndex", 0)
                put("selectedModel", model)
                put("supported", true)
            })
        }
        unsupportedFunctionTypes.forEach { (type, reason) ->
            add(buildJsonObject {
                put("functionType", type)
                put("configId", "")
                put("configName", "")
                put("modelIndex", 0)
                put("selectedModel", "")
                put("supported", false)
                put("reason", reason)
            })
        }
    }

    private fun providerItem(provider: AIProviderConfig): JsonObject = buildJsonObject {
        put("id", provider.id)
        put("name", provider.name)
        put("apiProviderType", provider.type.name)
        put("apiEndpoint", provider.baseUrl)
        put("modelName", provider.effectiveModel)
        put("modelList", stringArray(provider.models))
        put("apiKeySet", provider.firstUsableApiKey.isNotBlank())
        put("apiKeyPreview", maskSecret(provider.firstUsableApiKey))
        put("enabled", provider.isEnabled)
        put("customHeaders", buildJsonObject {
            provider.customHeaders.forEach { (name, value) -> put(name, value) }
        })
        put("useFullUrl", provider.useFullUrl)
        // 插件自己声明的供应商（清单里的 providers 段）与用户自建的同权，但作者通常要区分
        put("pluginOwned", PluginProviderIds.isPluginId(provider.id))
    }

    private val supportedRoles: List<FunctionRole> = listOf(
        FunctionRole(
            type = "CHAT",
            read = { defaultModel.getDefaultProviderId() to defaultModel.getDefaultModel() },
            write = { providerId, model -> defaultModel.setDefaultModel(providerId, model) },
        ),
        FunctionRole(
            type = "TITLE_GENERATION",
            read = { titleModel.getTitleProviderId() to titleModel.getTitleModel() },
            write = { providerId, model -> titleModel.setTitleModel(providerId, model) },
        ),
        FunctionRole(
            type = "SUMMARY",
            read = { compactionModel.getCompactionProviderId() to compactionModel.getCompactionModel() },
            write = { providerId, model -> compactionModel.setCompactionModel(providerId, model) },
        ),
        FunctionRole(
            type = "IMAGE_RECOGNITION",
            read = { visionModel.getVisionProviderId() to visionModel.getVisionModel() },
            write = { providerId, model -> visionModel.setVisionModel(providerId, model) },
        ),
        FunctionRole(
            type = "AUDIO_RECOGNITION",
            read = { sttSettings.getSttProviderId() to sttSettings.getSttModel() },
            write = { providerId, model -> sttSettings.setSttModel(providerId, model) },
        ),
        // Aharou 自己的角色，Operit 没有；插件在它那边按需用到就一并给
        FunctionRole(
            type = "IMAGE_GENERATION",
            read = { imageGenModel.getImageGenProviderId() to imageGenModel.getImageGenModel() },
            write = { providerId, model -> imageGenModel.setImageGenModel(providerId, model) },
        ),
    )

    private val unsupportedFunctionTypes: Map<String, String> = mapOf(
        "MEMORY" to "Aharou 的记忆是 Markdown 文件 + 图谱，没有单独的记忆处理模型角色",
        "UI_CONTROLLER" to "Aharou 没有 Operit 的界面自动化子智能体通道",
        "TRANSLATION" to "Aharou 没有内置翻译功能",
        "GREP" to "Aharou 的检索不经过单独的规划模型",
        "ROLE_RESPONSE_PLANNER" to "Aharou 没有多角色同场对话的发言排序器",
        "VIDEO_RECOGNITION" to "Aharou 不支持视频理解",
    )

    private class FunctionRole(
        val type: String,
        val read: suspend () -> Pair<String, String>,
        val write: suspend (String, String) -> Unit,
    )

    private fun unsupportedFunctionType(type: String): PluginHostException {
        val reason = unsupportedFunctionTypes[type]
        var message = if (reason == null) {
            "未知的 function_type：$type（可用：${(supportedRoles.map { it.type } + unsupportedFunctionTypes.keys).sorted().joinToString("/")}）"
        } else {
            "$type 不受支持：$reason"
        }
        if (reason != null) message += "；不支持的取值见 listFunctionModelConfigs 的 supported=false 项"
        return PluginHostException(if (reason == null) "PARAM_INVALID" else "UNSUPPORTED", message)
    }

    private suspend fun ensureActive(pluginId: String) {
        pluginManager.ensureLoaded()
        pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
    }

    private fun configNotFound(id: String): PluginHostException =
        PluginHostException("NOT_FOUND", "模型配置（供应商）不存在：$id")

    private fun parseProviderType(raw: String?): ProviderType? =
        raw?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
            ProviderType.values().firstOrNull { it.name.equals(value, ignoreCase = true) }
        }

    /** Operit 的密钥展示口径：`abc***xy`，够识别是哪把，又不至于整把漏出去。 */
    private fun maskSecret(value: String): String {
        if (value.isBlank()) return ""
        return if (value.length <= 4) "*".repeat(value.length) else "${value.take(3)}***${value.takeLast(2)}"
    }

    private fun stringArray(values: List<String>): JsonArray =
        buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private fun firstText(params: JsonObject, vararg keys: String): String? {
        keys.forEach { key -> params.text(key)?.let { return it } }
        return null
    }

    private fun JsonObject.requireConfigId(): String =
        text("config_id") ?: text("configId") ?: text("id")
        ?: throw PluginHostException("PARAM_MISSING", "missing parameter: config_id")

    private fun JsonObject.requireFunctionType(): String {
        val raw = text("function_type") ?: text("functionType")
            ?: throw PluginHostException("PARAM_MISSING", "missing parameter: function_type")
        return raw.trim().uppercase()
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()

    private fun JsonObject.stringList(key: String): List<String>? {
        val raw = this[key] ?: return null
        val values = when (raw) {
            is JsonArray -> raw.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> raw.contentOrNull.orEmpty().split(',')
            else -> return null
        }
        return values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /** `custom_headers` 接受对象或 `{"A":"B"}` 形态的字符串（Operit 的 JS 层传的是 stringify 后的串）。 */
    private fun JsonObject.headerMap(): Map<String, String>? {
        val raw = this["custom_headers"] ?: this["customHeaders"] ?: return null
        val obj = when (raw) {
            is JsonObject -> raw
            is JsonPrimitive -> runCatching {
                val parsed = kotlinx.serialization.json.Json.parseToJsonElement(raw.contentOrNull.orEmpty())
                parsed as? JsonObject
            }.getOrNull()
            else -> null
        } ?: throw PluginHostException("PARAM_INVALID", "custom_headers 必须是 JSON 对象")
        return obj.mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
        }.toMap()
    }

    private companion object {
        const val MAX_TTS_TEST_CHARS = 2_000
        const val MAX_RETURNED_MODELS = 100

        /** setSpeechServicesConfig 认识的键；除此之外的键原样进 `ignored`，不静默吞掉。 */
        val knownSpeechKeys = setOf(
            "tts_service_type",
            "tts_provider",
            "tts_provider_id",
            "tts_model",
            "tts_voice",
            "tts_voice_id",
            "tts_voice_user",
            "tts_user_voice",
            "stt_provider",
            "stt_provider_id",
            "stt_model",
            "auto_read",
            "chat_mode",
            "tts_headers",
            "tts_http_config",
            "tts_locale",
            "tts_response_pipeline",
            "tts_cleaner_regexs",
            "tts_speech_rate",
            "tts_pitch",
            "stt_service_type",
            "stt_http_config",
        )
    }
}
