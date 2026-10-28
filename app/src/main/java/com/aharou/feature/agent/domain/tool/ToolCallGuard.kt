package com.aharou.feature.agent.domain.tool

import com.aharou.feature.agent.domain.memory.MemoryRepository
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.domain.memory.MemoryType
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工具重复调用守卫。
 *
 * 模型偶尔会拿完全相同的参数反复打同一个工具：失败后不改参数重试，或成功但毫无进展地空转。
 * 这类调用执行再多遍也不会有不同结果，只会烧额度、把上下文塞满错误输出。这里按会话记住最近
 * 几次调用，命中下列任一条件就不再执行，直接把「为什么被拦 + 接下来该做什么」回给模型：
 *
 * - 同名同参连续失败 [FAIL_STREAK_LIMIT] 次；
 * - 同名同参连续执行 [IDLE_STREAK_LIMIT] 次，且结果内容毫无变化。
 *
 * 拦截不写记忆，只写当前工作区的项目记忆条目（跨会话有效、切工作区就看不到）。
 */
@Singleton
class ToolCallGuard @Inject constructor(
    private val memoryRepository: MemoryRepository,
) {
    companion object {
        /** 同名同参连续失败到这个次数就不再执行，先让模型改策略。 */
        private const val FAIL_STREAK_LIMIT = 2

        /** 同名同参连续执行、结果毫无变化到这个次数，视为空转。 */
        private const val IDLE_STREAK_LIMIT = 3

        /** 每个会话保留的最近调用条数：够判定连续 streak，又不会无限增长。 */
        private const val HISTORY_LIMIT = 8

        private const val LESSON_NAME = "repeated_tool_call_guard"

        private const val LESSON_DESCRIPTION =
            "本工作区启用工具重复调用守卫后留下的记录：什么情况会被拦、正确做法是什么"

        const val GUARDED_CODE = "TOOL_CALL_GUARDED"

        private val COUNT_REGEX = Regex("累计拦截：(\\d+) 次")
        private val FIRST_REGEX = Regex("首次触发：(.+)")

        private fun formatTime(millis: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(millis))

        /** 同名同参的判定与工作流里的批内去重保持一致：工具名 + 规范化后的参数对象。 */
        private fun signatureOf(toolName: String, args: Map<String, JsonElement>): String =
            toolName + "\u0000" + JsonObject(args.filterKeys { it != DISPLAY_ONLY_ARG }).toString()

        /**
         * 只用于展示、不参与「是否同一次调用」判定的参数。
         * tool_title 是给人看的卡标题，换个措辞是日常行为；把它算进签名等于给守卫留一个后门
         * （实测：三次调用只改标题就完全不触发拦截）。
         */
        private const val DISPLAY_ONLY_ARG = "tool_title"
    }

    private data class Attempt(
        val signature: String,
        val isError: Boolean,
        val resultDigest: Int,
    )

    /** 按会话隔离：同一进程常有多个会话并行跑工具，历史串台会误拦别的会话。 */
    private val histories = ConcurrentHashMap<String, ArrayDeque<Attempt>>()

    /**
     * 执行前的判定；返回非空表示本次调用应被拦下，内容就是回给模型的反思提示。
     */
    fun beforeInvoke(
        toolName: String,
        args: Map<String, JsonElement>,
        context: AgentContext?,
    ): String? {
        val signature = signatureOf(toolName, args)
        val recent = synchronized(historyOf(context)) { historyOf(context).toList() }
        val streak = recent.takeLastWhile { it.signature == signature }
        if (streak.isEmpty()) return null

        val failStreak = streak.takeLastWhile { it.isError }.size
        if (failStreak >= FAIL_STREAK_LIMIT) {
            return persistAndBuild(toolName, "完全相同的调用已连续失败 $failStreak 次", context)
        }

        val lastDigest = streak.last().resultDigest
        val idleStreak = streak.takeLastWhile { it.resultDigest == lastDigest }.size
        if (idleStreak >= IDLE_STREAK_LIMIT) {
            return persistAndBuild(toolName, "完全相同的调用已连续 $idleStreak 次没有任何变化", context)
        }
        return null
    }

    /** 执行后登记结果，供下一次判定使用。 */
    fun record(
        toolName: String,
        args: Map<String, JsonElement>,
        context: AgentContext?,
        isError: Boolean,
        resultText: String,
    ) {
        val deque = historyOf(context)
        synchronized(deque) {
            deque.addLast(Attempt(signatureOf(toolName, args), isError, resultText.hashCode()))
            while (deque.size > HISTORY_LIMIT) deque.removeFirst()
        }
    }

    private fun historyOf(context: AgentContext?): ArrayDeque<Attempt> =
        histories.getOrPut(context?.sessionId ?: "no-session") { ArrayDeque() }

    private fun persistAndBuild(toolName: String, reason: String, context: AgentContext?): String {
        runCatching { persistLesson(toolName, context) }
        return buildString {
            append("【重复调用已被拦截】工具 `").append(toolName).append("` ").append(reason)
            append("，本次未执行。\n\n接下来：\n")
            append("1. 先看上面那条错误原文，定位真正原因（路径错？内容对不上？参数缺了？）；\n")
            append("2. 不许原样重试——改参数、换工具，或先用 readFile 看清最新内容再动手；\n")
            append("3. 确实绕不过去，就直接告诉用户卡在哪，别继续空转。")
        }
    }

    /**
     * 把这次拦截记进当前工作区的项目记忆（跨会话有效、切换工作区看不到）：
     * 同一条固定条目只更新计数与最近时间，不新增、不刷屏。
     */
    private fun persistLesson(toolName: String, context: AgentContext?) {
        val projectRoot = context?.projectRoot?.takeIf { it.isNotBlank() } ?: return
        val now = formatTime(System.currentTimeMillis())
        val existing = memoryRepository.loadContent(LESSON_NAME, projectRoot, MemoryScope.PROJECT)
        val count = (COUNT_REGEX.find(existing.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0) + 1
        val first = FIRST_REGEX.find(existing.orEmpty())?.groupValues?.get(1)?.trim() ?: now
        val content = buildString {
            append("# 重复工具调用守卫\n\n")
            append("- 首次触发：").append(first).append('\n')
            append("- 累计拦截：").append(count).append(" 次\n")
            append("- 最近触发：").append(now).append(" · 工具 `").append(toolName).append("`\n\n")
            append("## 什么情况会被拦\n\n")
            append("- 同一工具 + 完全相同的参数，连续失败 ").append(FAIL_STREAK_LIMIT).append(" 次；\n")
            append("- 同一工具 + 完全相同的参数，连续执行 ").append(IDLE_STREAK_LIMIT).append(" 次且结果毫无变化。\n\n")
            append("## 正确做法\n\n")
            append("- 先读错误原文定位原因，再决定改什么；\n")
            append("- 禁止原样重试：改参数、换工具，或先用 readFile 看清最新内容；\n")
            append("- 确实绕不过去，就直接向用户说明卡点与已完成的部分。\n")
        }
        memoryRepository.saveMemory(
            name = LESSON_NAME,
            description = LESSON_DESCRIPTION,
            content = content,
            scope = MemoryScope.PROJECT,
            projectRoot = projectRoot,
            type = MemoryType.WORKFLOW,
        )
    }

    /** 把工具包一层守卫；已是守卫包装的实例不重复包装。 */
    fun wrap(tool: AgentTool): AgentTool {
        if (tool is GuardedBridge) return tool
        return if (tool is StreamingAgentTool) {
            GuardedStreamingTool(tool, this)
        } else {
            GuardedTool(tool, this)
        }
    }

    /** 标记接口：识别「已经被包过」的工具，避免重复包裹导致守卫执行两遍。 */
    private interface GuardedBridge

    private open class GuardedTool(
        private val delegate: AgentTool,
        private val guard: ToolCallGuard,
    ) : AgentTool(), GuardedBridge {
        override val name: String get() = delegate.name
        override val description: String get() = delegate.description
        override val parameters: Map<String, ToolParameter> get() = delegate.parameters
        override val permissionPolicy: ToolPermissionPolicy get() = delegate.permissionPolicy
        override val capabilities: Set<ToolCapability> get() = delegate.capabilities
        override val acceptsSecrets: Boolean get() = delegate.acceptsSecrets
        override val deferredLoading: Boolean get() = delegate.deferredLoading

        override fun effectivePermissionPolicy(mode: AgentMode): ToolPermissionPolicy =
            delegate.effectivePermissionPolicy(mode)

        override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> =
            delegate.effectiveCapabilities(args)

        override fun isCallSensitive(args: Map<String, JsonElement>): Boolean =
            delegate.isCallSensitive(args)

        override fun buildPermissionRequest(
            callId: String,
            args: Map<String, JsonElement>,
            argsPreview: String,
        ): PendingToolPermission = delegate.buildPermissionRequest(callId, args, argsPreview)

        override suspend fun execute(args: Map<String, JsonElement>): ToolResult =
            runGuarded(null, args) { delegate.execute(args) }

        override suspend fun executeWithContext(
            args: Map<String, JsonElement>,
            context: AgentContext,
        ): ToolResult = runGuarded(context, args) { delegate.executeWithContext(args, context) }

        private suspend fun runGuarded(
            context: AgentContext?,
            args: Map<String, JsonElement>,
            run: suspend () -> ToolResult,
        ): ToolResult {
            guard.beforeInvoke(name, args, context)?.let { blocked ->
                return ToolResult.Error(blocked, GUARDED_CODE)
            }
            val result = run()
            guard.record(name, args, context, result is ToolResult.Error, result.toString())
            return result
        }
    }

    private class GuardedStreamingTool(
        private val delegate: AgentTool,
        private val guard: ToolCallGuard,
    ) : AgentTool(), StreamingAgentTool, GuardedBridge {
        override val name: String get() = delegate.name
        override val description: String get() = delegate.description
        override val parameters: Map<String, ToolParameter> get() = delegate.parameters
        override val permissionPolicy: ToolPermissionPolicy get() = delegate.permissionPolicy
        override val capabilities: Set<ToolCapability> get() = delegate.capabilities
        override val acceptsSecrets: Boolean get() = delegate.acceptsSecrets
        override val deferredLoading: Boolean get() = delegate.deferredLoading

        override fun effectivePermissionPolicy(mode: AgentMode): ToolPermissionPolicy =
            delegate.effectivePermissionPolicy(mode)

        override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> =
            delegate.effectiveCapabilities(args)

        override fun isCallSensitive(args: Map<String, JsonElement>): Boolean =
            delegate.isCallSensitive(args)

        override fun buildPermissionRequest(
            callId: String,
            args: Map<String, JsonElement>,
            argsPreview: String,
        ): PendingToolPermission = delegate.buildPermissionRequest(callId, args, argsPreview)

        override suspend fun execute(args: Map<String, JsonElement>): ToolResult =
            runGuarded(null, args) { delegate.execute(args) }

        override suspend fun executeWithContext(
            args: Map<String, JsonElement>,
            context: AgentContext,
        ): ToolResult = runGuarded(context, args) { delegate.executeWithContext(args, context) }

        override fun executeStream(
            args: Map<String, JsonElement>,
            context: AgentContext,
        ): Flow<ToolStreamEvent> = flow {
            guard.beforeInvoke(name, args, context)?.let { blocked ->
                emit(ToolStreamEvent.Completed(ToolResult.Error(blocked, GUARDED_CODE)))
                return@flow
            }
            var finalResult: ToolResult? = null
            (delegate as StreamingAgentTool).executeStream(args, context).collect { event ->
                if (event is ToolStreamEvent.Completed) finalResult = event.result
                emit(event)
            }
            finalResult?.let { result ->
                guard.record(name, args, context, result is ToolResult.Error, result.toString())
            }
        }

        private suspend fun runGuarded(
            context: AgentContext?,
            args: Map<String, JsonElement>,
            run: suspend () -> ToolResult,
        ): ToolResult {
            guard.beforeInvoke(name, args, context)?.let { blocked ->
                return ToolResult.Error(blocked, GUARDED_CODE)
            }
            val result = run()
            guard.record(name, args, context, result is ToolResult.Error, result.toString())
            return result
        }
    }
}
