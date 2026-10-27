package com.aharou.feature.agent.domain.mobileagent

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.LlmCallRecordDao
import com.aharou.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aharou.feature.agent.data.remote.anthropic.AnthropicApi
import com.aharou.feature.agent.data.remote.gemini.GeminiApi
import com.aharou.feature.agent.data.remote.openai.OpenAIApi
import com.aharou.feature.agent.domain.model.AgentMessage
import android.os.SystemClock
import com.aharou.feature.agent.domain.provider.AIProvider
import com.aharou.feature.agent.domain.provider.AIResponse
import com.aharou.feature.agent.domain.provider.AnthropicAdapter
import com.aharou.feature.agent.domain.provider.GeminiAdapter
import com.aharou.feature.agent.domain.provider.OpenAIAdapter
import com.aharou.feature.agent.domain.provider.fixedTemperature
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.settings.data.remote.ModelMetadataService
import com.aharou.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aharou.feature.settings.data.repository.GeneralSettingsRepository
import com.aharou.feature.settings.data.repository.ProviderKeyRotator
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 手机操作子代理的运行结果：可区分的收尾域，不抛异常。status 用于主 agent 与 UI 判读。
 */
sealed interface OperatorOutcome {
    val steps: Int
    val traceId: String
    val status: String

    /** 达到目标，answer 是从屏上读到／算出来的答案。 */
    data class Done(override val steps: Int, override val traceId: String, val answer: String) : OperatorOutcome {
        override val status get() = "done"
    }

    /** 需要用户：模型提问，或某步只有用户能做（密码、验证码、支付确认）。 */
    data class Ask(override val steps: Int, override val traceId: String, val question: String) : OperatorOutcome {
        override val status get() = "ask"
    }

    /** 无法继续：模型不可用、产不出动作、空转等。 */
    data class Failed(override val steps: Int, override val traceId: String, val message: String) : OperatorOutcome {
        override val status get() = "failed"
    }

    /** 用完步数仍未结束。 */
    data class MaxSteps(override val steps: Int, override val traceId: String, val note: String) : OperatorOutcome {
        override val status get() = "max_steps"
    }

    /** 给主 agent 的一段人类可读结论（一次调用一个结果的「结果」）。 */
    fun render(): String {
        val head = when (status) {
            "done" -> "手机操作子代理已完成。"
            "ask" -> "手机操作子代理停下，需要用户。"
            "max_steps" -> "手机操作子代理用完步数仍未完成。"
            else -> "手机操作子代理无法继续。"
        }
        val body = when (this) {
            is Done -> answer
            is Ask -> question
            is Failed -> message
            is MaxSteps -> note
        }
        return buildString {
            append(head).append("（").append(steps).append(" 步）")
            if (body.isNotBlank()) append('\n').append(body)
            if (traceId.isNotBlank()) append("\nTrace: ").append(traceId)
        }
    }
}

/**
 * 手机操作子代理：把「看屏 → 决策 → 动作 → 再看屏」的多步循环封在内部，
 * 主 agent 只看到「一次调用 + 一个结果」，不再逐步吃上下文。
 *
 * 观测是「截图 + 一行 caption」（VdTool 已带坐标网格，网格数字是像素参考），无障碍节点树为
 * 可选第二输入（由 [MobileHand.readsNodeTree] 决定）。模型交回的坐标一律是 0..1000 归一化值，
 * 由 [MobileAction.toPlan] 在真正下发前换算成像素。决策让模型按固定形制输出，解析器容错。
 *
 * 动作之后先等「界面静默」再重新观测（[awaitSettled]）：动作前取一屏指纹，动作后轮询到「已经变了
 * 且连续几次相同」才返回，超时就降级为返回当前观测，不算失败。每步的前后指纹写进 trace；
 * 空转判定也因此从「同动作」升级为「同动作且同屏」。[MAX_IDLE_STEPS] 步无效即中止并返回失败。
 * 收尾用 [OperatorOutcome] 表达（不抛异常）。
 */
@Singleton
class MobileOperator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val traceStore: MobileTraceStore,
    private val aiProviderRepository: AIProviderRepository,
    private val openAIApi: OpenAIApi,
    private val anthropicApi: AnthropicApi,
    private val geminiApi: GeminiApi,
    private val modelMetadataService: ModelMetadataService,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val providerKeyRotator: ProviderKeyRotator,
    private val sessionUseCase: SessionUseCase,
    private val llmCallRecordDao: LlmCallRecordDao,
) {

    /**
     * 跑一次手机操作任务。
     *
     * @param hand 操作对象（影子屏/无障碍），由调用方在调用时传入，避免 DI 循环。
     * @param app 目标 App（名称或包名）；非空时先打开它。
     * @param maxSteps 步数上限，≤0 表示不设限。
     * @param modelOverride 指定模型（独立小模型）；为空则复用会话/默认模型。
     */
    suspend fun run(
        hand: MobileHand,
        goal: String,
        app: String?,
        maxSteps: Int,
        modelOverride: String?,
        sessionId: String?,
    ): OperatorOutcome {
        val trace = traceStore.start(goal = goal, app = app.orEmpty(), sessionId = sessionId)
        val provider = runCatching { createProvider(sessionId, modelOverride) }.getOrNull()
            ?: return finish(trace, OperatorOutcome.Failed(0, trace.id, "没有可用于手机操作的模型：请先在设置里配置并选中一个支持图片输入的模型"))

        val component = app?.takeIf { it.isNotBlank() }?.let { resolveComponent(it) ?: it }
        val prepareError = runCatching { hand.prepare(component) }.getOrElse { it.message ?: "准备失败" }
        if (prepareError != null) {
            return finish(trace, OperatorOutcome.Failed(0, trace.id, "无法准备操作对象：$prepareError"))
        }

        val progress = mutableListOf<String>()
        var idle = 0
        var lastAction: Attempt? = null
        val limit = if (maxSteps > 0) maxSteps else Int.MAX_VALUE

        var observation: HandObservation? = observe(hand)
        (observation?.error)?.let {
            return finish(trace, OperatorOutcome.Failed(0, trace.id, "看不到屏幕：$it"))
        }

        var stepNo = 0
        while (stepNo < limit) {
            stepNo++
            val obs = observation ?: observe(hand)

            val decision = runCatching { decide(provider, goal, progress, obs, sessionId) }
                .getOrElse { return finish(trace, OperatorOutcome.Failed(stepNo, trace.id, "模型调用失败：${it.message ?: it.javaClass.simpleName}")) }
            val step = decision.step
            if (step == null) {
                trace.step(stepNo, obs.caption, obs.image, thought = null, action = null,
                    params = null, raw = decision.raw, latencyMs = decision.latencyMs,
                    error = "模型没有产出可用的动作")
                return finish(trace, OperatorOutcome.Failed(stepNo, trace.id, "模型没有产出可用的动作，已中止"))
            }

            val planned = MobileAction.toPlan(step, obs.width, obs.height)
            val entry = step.action.replace("\n", " ").replace("\"", "")
            when (planned) {
                is PlannedAction.Finish -> {
                    trace.step(stepNo, obs.caption, obs.image, step.thought, step.action, null, decision.raw, decision.latencyMs, null,
                        before = obs.fingerprint)
                    val outcome = when (planned.status) {
                        "done" -> OperatorOutcome.Done(stepNo, trace.id, planned.text)
                        "ask" -> OperatorOutcome.Ask(stepNo, trace.id, planned.text)
                        else -> OperatorOutcome.Failed(stepNo, trace.id, planned.text.ifBlank { "模型终止了任务" })
                    }
                    return finish(trace, outcome)
                }

                is PlannedAction.Wait -> {
                    val before = obs.fingerprint
                    delay((planned.seconds * 1000).toLong())
                    val after = runCatching { hand.fingerprint() }.getOrNull()
                    trace.step(stepNo, obs.caption, obs.image, step.thought, step.action, null, decision.raw, decision.latencyMs, null,
                        before = before, after = after)
                    progress.add("$entry; Result: 等待 ${planned.seconds}s")
                    observation = null
                    continue
                }

                is PlannedAction.Open -> {
                    val target = resolveComponent(planned.app)
                    if (target == null) {
                        trace.step(stepNo, obs.caption, obs.image, step.thought, step.action, null, decision.raw, decision.latencyMs, "找不到 App：${planned.app}")
                        progress.add("$entry; Result: 找不到 App「${planned.app}」")
                        idle++
                        if (idle >= MAX_IDLE_STEPS) return finish(trace, idle(trace, hand, stepNo))
                        observation = null
                        continue
                    }
                    val launchArgs = mapOf("action" to JsonPrimitive("launch"), "component" to JsonPrimitive(target))
                    val before = obs.fingerprint
                    val res = runCatching { hand.act(launchArgs) }
                        .getOrElse { HandActResult(false, it.message ?: "启动失败") }
                    val after = if (res.ok) awaitSettled(hand, before, SETTLE_TIMEOUT_LAUNCH_MS) else null
                    trace.step(stepNo, obs.caption, obs.image, step.thought, step.action, step.arguments, decision.raw, decision.latencyMs,
                        res.message.takeIf { !res.ok }, dispatched = launchArgs, before = before, after = after)
                    progress.add(if (res.ok) entry else "$entry; Result: ${res.message}")
                    lastAction = Attempt(launchArgs, before, after)
                    idle = 0
                    observation = null
                    continue
                }

                is PlannedAction.Invalid -> {
                    trace.step(stepNo, obs.caption, obs.image, step.thought, step.action, step.arguments, decision.raw, decision.latencyMs,
                        planned.reason, before = obs.fingerprint)
                    progress.add("$entry; Result: ${planned.reason}")
                    idle++
                    if (idle >= MAX_IDLE_STEPS) return finish(trace, idle(trace, hand, stepNo))
                    observation = null
                    continue
                }

                is PlannedAction.Device -> {
                    // 空转：上一次就是同一个动作，而且那次动作并没有改变画面——再重复一次也是白做。
                    // 拿不到指纹时退化成旧行为：同一个动作连着做三次也算空转。
                    val before = obs.fingerprint
                    val last = lastAction
                    val same = last != null && MobileAction.sameAction(last.args, planned.args)
                    val lastHadNoEffect = last != null && last.before != null && last.after != null &&
                        last.before.sameContent(last.after)
                    val noFingerprint = last != null && (last.before == null || last.after == null)
                    if (same && (lastHadNoEffect || (noFingerprint && last!!.repeats >= 2))) {
                        progress.add("$entry; Note: 同一个动作已经连续做了几次，屏幕没有变化——换一种做法，或用 terminate(status=\"failure\") 结束")
                        trace.step(stepNo, obs.caption, obs.image, step.thought, step.action, step.arguments, decision.raw, decision.latencyMs,
                            "空转（同动作且屏未变）", dispatched = planned.args, before = before, after = before)
                        idle++
                        if (idle >= MAX_IDLE_STEPS) return finish(trace, idle(trace, hand, stepNo))
                        observation = null
                        continue
                    }

                    val res = runCatching { hand.act(planned.args) }
                        .getOrElse { HandActResult(false, it.message ?: "动作失败") }
                    val after = if (res.ok) awaitSettled(hand, before, settleTimeoutMs(planned.args)) else null
                    trace.step(stepNo, obs.caption, obs.image, step.thought, step.action, step.arguments, decision.raw, decision.latencyMs,
                        res.message.takeIf { !res.ok }, dispatched = planned.args, before = before, after = after)
                    if (res.ok) {
                        idle = 0
                        lastAction = Attempt(planned.args, before, after, if (same) last!!.repeats + 1 else 1)
                        progress.add(entry)
                    } else {
                        progress.add("$entry; Result: ${res.message}")
                    }
                    observation = null
                    continue
                }
            }
        }
        return finish(trace, OperatorOutcome.MaxSteps(stepNo, trace.id, "已用完 $maxSteps 步仍未结束"))
    }

    // ------------------------------------------------------------------ 决策

    private data class Decision(val step: MobileStep?, val raw: String, val latencyMs: Long)

    /** 上一次设备动作、它前后的屏、以及同动作连续出现了几次：空转判定用「同动作 + 上次没改变画面」。 */
    private data class Attempt(
        val args: Map<String, JsonElement>,
        val before: ScreenFingerprint?,
        val after: ScreenFingerprint?,
        val repeats: Int = 1,
    )

    /** 让模型给出下一步；回复不成形时最多重问 [PARSE_RETRIES] 次。 */
    private suspend fun decide(
        provider: AIProvider,
        goal: String,
        progress: List<String>,
        obs: HandObservation,
        sessionId: String?,
    ): Decision {
        val progressText = progress.mapIndexed { i, s -> "Step ${i + 1}: $s; " }.joinToString("")
        var content = MobilePrompt.userText(goal, progressText, screenSize(obs))
        obs.nodesText?.takeIf { it.isNotBlank() }?.let { content += "\n\n" + MobilePrompt.nodesText(it) }
        var messages: List<AgentMessage> = listOf(AgentMessage.UserMessage(content = content, images = listOfNotNull(obs.image)))
        val started = System.currentTimeMillis()
        var raw = ""
        repeat(PARSE_RETRIES) { attempt ->
            val response = complete(provider, messages, sessionId)
            raw = response.content
            MobileStepParser.parse(raw)?.let { step ->
                return Decision(step, raw, System.currentTimeMillis() - started)
            }
            FileLogger.w(TAG, "手机操作一步解析失败（第 ${attempt + 1} 次）：${raw.take(300)}")
            if (attempt + 1 < PARSE_RETRIES) {
                messages = listOf(
                    AgentMessage.UserMessage(content = content, images = listOfNotNull(obs.image)),
                    AgentMessage.AssistantMessage(content = raw),
                    AgentMessage.UserMessage(content = "这不是要求的格式。请用 Thought、Action 和一个调用 ${MobilePrompt.TOOL_NAME} 的 <tool_call> 块重新回复。"),
                )
            }
        }
        return Decision(null, raw, System.currentTimeMillis() - started)
    }

    /** 发一次补全并落一条调用统计（失败照抛，由循环按「产不出动作」处理）。 */
    private suspend fun complete(provider: AIProvider, messages: List<AgentMessage>, sessionId: String?): AIResponse {
        val startedElapsed = SystemClock.elapsedRealtime()
        val startedWall = System.currentTimeMillis()
        var response: AIResponse? = null
        var error: String? = null
        try {
            val result = provider.complete(systemPrompt = MobilePrompt.SYSTEM_PROMPT, messages = messages, tools = emptyList())
            response = result
            return result
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
            throw e
        } finally {
            runCatching {
                llmCallRecordDao.insert(
                    LlmCallRecordEntity(
                        sessionId = sessionId,
                        providerId = provider.providerId.ifBlank { null },
                        model = provider.model,
                        kind = "mobile_task",
                        inputTokens = response?.inputTokens ?: 0,
                        outputTokens = response?.outputTokens ?: 0,
                        cachedInputTokens = response?.cachedInputTokens ?: 0,
                        cacheCreationTokens = response?.cacheCreationTokens ?: 0,
                        ttfbMillis = null,
                        durationMillis = (SystemClock.elapsedRealtime() - startedElapsed).toInt(),
                        status = if (error == null) "success" else "error",
                        errorMessage = error,
                        stopReason = response?.stopReason,
                        createdAt = startedWall,
                    )
                )
            }.onFailure { FileLogger.w(TAG, "记录手机操作调用统计失败：${it.message}") }
        }
    }

    // ------------------------------------------------------------------ 观测

    /** 看屏：让该手观测当前屏幕。 */
    private suspend fun observe(hand: MobileHand): HandObservation {
        return runCatching { hand.observe() }
            .getOrElse { HandObservation(caption = "", image = null, error = it.message ?: it.javaClass.simpleName) }
    }

    /** 给模型看的屏幕像素尺寸（归一化坐标换算用）；尺寸未知返回 null。 */
    private fun screenSize(obs: HandObservation): String? =
        if (obs.width > 0 && obs.height > 0) "${obs.width}×${obs.height}" else null

    /** 动作后等静默的总超时：启动 App 落屏慢一些，其余统一 [SETTLE_TIMEOUT_MS]。 */
    private fun settleTimeoutMs(args: Map<String, JsonElement>): Long {
        val action = (args["action"] as? JsonPrimitive)?.contentOrNull?.trim()
        return if (action == "launch") SETTLE_TIMEOUT_LAUNCH_MS else SETTLE_TIMEOUT_MS
    }

    /**
     * 动作之后等界面静默：先等它「变了」（与 [before] 不同），再要求连续 [SETTLE_STABLE_POLLS] 次
     * 采样一致才返回。总超时 [timeoutMs] 后返回最后一次采样——超时只说明没等到静下来，
     * 不算动作失败。拿不到指纹（[before] 为空或这只手不支持）时返回 null，循环退化成直接重看。
     */
    private suspend fun awaitSettled(hand: MobileHand, before: ScreenFingerprint?, timeoutMs: Long): ScreenFingerprint? {
        if (before == null) return null
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var previous = before
        var changed = false
        var stable = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(SETTLE_POLL_MS)
            // 取不到这一帧就交回 null：宁可让循环当成「判不了」，也不要把动作误判成「没效果」。
            val current = runCatching { hand.fingerprint() }.getOrNull() ?: return null
            if (!changed) {
                if (!current.sameContent(before)) {
                    changed = true
                    stable = 1
                }
            } else if (current.sameContent(previous)) {
                stable++
                if (stable >= SETTLE_STABLE_POLLS) return current
            } else {
                stable = 1
            }
            previous = current
        }
        return previous
    }

    // ------------------------------------------------------------------ 收尾/工具

    private fun idle(trace: MobileTrace, hand: MobileHand, steps: Int): OperatorOutcome {
        // 影子屏不读节点树，用它区分「影子屏 / 无障碍」这只手。
        val noun = if (hand.readsNodeTree) "屏幕" else "影子屏"
        return OperatorOutcome.Failed(
            steps, trace.id,
            "连续 $MAX_IDLE_STEPS 步在${noun}上都没有产生有效动作（动作不认识，或同一个动作反复做而屏幕没有变化），已中止",
        )
    }

    private fun finish(trace: MobileTrace, outcome: OperatorOutcome): OperatorOutcome {
        trace.end(outcome.status, renderMessage(outcome), outcome.steps)
        return outcome
    }

    private fun renderMessage(outcome: OperatorOutcome): String = when (outcome) {
        is OperatorOutcome.Done -> outcome.answer
        is OperatorOutcome.Ask -> outcome.question
        is OperatorOutcome.Failed -> outcome.message
        is OperatorOutcome.MaxSteps -> outcome.note
    }

    /** App 名称/包名 → 包名（含 `/` 的组件原样返回，已是包名的直接返回，否则按显示名模糊匹配）。 */
    private fun resolveComponent(app: String): String? {
        val query = app.trim()
        if (query.isEmpty()) return null
        if (query.contains("/")) return query
        val pm = context.packageManager
        if (runCatching { pm.getPackageInfo(query, 0) }.isSuccess) return query
        val norm = query.lowercase()
        return runCatching {
            pm.getInstalledApplications(0)
                .firstOrNull { pm.getApplicationLabel(it).toString().lowercase().contains(norm) }
                ?.packageName
        }.getOrNull()
    }

    // ------------------------------------------------------------------ 模型

    /** 解析要用的 provider 配置：优先 [modelOverride] 所在 provider，其次会话绑定，最后默认模型。 */
    private suspend fun resolveConfig(sessionId: String?, modelOverride: String?): AIProviderConfig? {
        if (modelOverride != null) {
            val all = runCatching { aiProviderRepository.getAllProviders().first() }.getOrNull().orEmpty()
            all.firstOrNull { it.isEnabled && it.hasUsableApiKey && modelOverride in it.models }
                ?.let { return it.copy(selectedModel = modelOverride) }
        }
        if (sessionId != null) {
            val session = runCatching { sessionUseCase.getSessionById(sessionId) }.getOrNull()
            val boundProvider = session?.providerId
            if (!boundProvider.isNullOrBlank()) {
                val config = aiProviderRepository.getProviderById(boundProvider)
                if (config != null && config.isEnabled && config.hasUsableApiKey) {
                    val boundModel = session?.model
                    if (boundModel.isNullOrBlank() || boundModel in config.models) {
                        return if (boundModel.isNullOrBlank()) config else config.copy(selectedModel = boundModel)
                    }
                }
            }
        }
        val defaultProviderId = defaultModelSettingsRepository.getDefaultProviderId()
        val defaultModel = defaultModelSettingsRepository.getDefaultModel()
        if (defaultProviderId.isNotBlank() && defaultModel.isNotBlank()) {
            val config = aiProviderRepository.getProviderById(defaultProviderId)
            if (config != null && config.isEnabled && config.hasUsableApiKey && defaultModel in config.models) {
                return config.copy(selectedModel = defaultModel)
            }
        }
        return null
    }

    /** 按配置造一个独立的 provider 实例（不占用主对话的单例）。 */
    private suspend fun createProvider(sessionId: String?, modelOverride: String?): AIProvider? {
        val config = resolveConfig(sessionId, modelOverride) ?: return null
        if (config.effectiveModel.isBlank()) return null
        val provider: AIProvider = when (config.type) {
            ProviderType.ANTHROPIC -> AnthropicAdapter(anthropicApi).also { it.cacheBreakpointsEnabled = config.anthropicCacheBreakpoints }
            ProviderType.GEMINI -> GeminiAdapter(geminiApi)
            else -> OpenAIAdapter(openAIApi).also { it.chatCacheKeyEnabled = config.openaiChatCacheKey }
        }
        provider.apiKey = providerKeyRotator.activeKey(config, sessionId) ?: return null
        provider.baseUrl = config.baseUrl
        provider.model = config.effectiveModel
        provider.useFullUrl = config.useFullUrl
        provider.useResponseApi = config.useResponseApi
        provider.providerId = config.id
        provider.logSessionId = sessionId
        provider.customHeaders = config.customHeaders
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        provider.maxOutputTokens = metadata.outputTokens
        provider.temperature = if (metadata.supportsCustomTemperature) fixedTemperature(config.effectiveModel) else null
        provider.firstByteTimeoutMs = generalSettingsRepository.firstByteTimeoutMs()
        provider.streamIdleTimeoutMs = generalSettingsRepository.streamIdleTimeoutMs()
        provider.maxNetworkRetries = generalSettingsRepository.maxNetworkRetries()
        return provider
    }

    companion object {
        private const val TAG = "MobileOperator"

        /** 连续这么多步没有产生有效动作（动作不认识 / 同一动作反复做）即中止。 */
        const val MAX_IDLE_STEPS = 6

        /** 回复不成形时最多重问几次。 */
        const val PARSE_RETRIES = 3

        /** 动作后等界面静默的轮询间隔。 */
        const val SETTLE_POLL_MS = 250L

        /** 界面变化后要连续这么多次采样一致才算静下来。 */
        const val SETTLE_STABLE_POLLS = 2

        /** 普通动作后等静默的总超时。 */
        const val SETTLE_TIMEOUT_MS = 3_000L

        /** 启动 App 后等静默的总超时（冷启动 + 落屏更慢）。 */
        const val SETTLE_TIMEOUT_LAUNCH_MS = 6_000L
    }
}
