package com.aharou.feature.agent.domain.workflow

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.model.id
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ToolCall
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.PathHomeResolver
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

/**
 * 分级裁剪的规划与执行：级别定义（L1 清旧 reasoning → L2 大工具结果与长参数落盘换引用 → L3 骨架化）、
 * 候选迭代的挑选、「刚好够用」的最小前缀计算，以及落盘/骨架化的具体改写。
 *
 * 从 [ContextCompactor] 拆出来的只是职责与行数：预算计算、三级降级与校验仍留在 [ContextCompactor]，
 * 它把裁剪这一层委托过来。规则本身一字未改，日志标签也沿用压缩的那个。
 */
internal class ContextTrimPlanner(
    private val fileAccess: FileAccessProvider,
    private val pathHomeResolver: PathHomeResolver
) {
    internal companion object {
        /** 日志标签与 [ContextCompactor] 一致：裁剪日志仍归在压缩的 tag 下，检索方式不变。 */
        private const val TAG = ContextCompactor.TAG

        /**
         * 分级裁剪：三级门槛（L2 落盘、L3 骨架化）与「最新几轮不裁」的轮数。参考 openminis
         * `ContextTrimPlanner` 的现场：长任务堆到 341,873 tokens、压缩反复触发却零释放，因为保留段本身就是整个任务。
         */
        private const val TRIM_SHRINK_CHARS = 2_000
        private const val TRIM_SKELETON_CHARS = 200
        internal const val TRIM_KEEP_RECENT_ITERATIONS = 3
        internal const val TRIM_KEEP_RECENT_IN_TAIL = 1

        /** 裁剪标记：出现在 modelResult / 工具参数里就说明这一条已经裁过（幂等，也是二次裁剪的识别依据）。 */
        private const val TRIM_PREFIX = "[context trim]"
    }

    fun storeOriginalToolOutput(message: AgentMessage.ToolResultMessage): String? {
        return try {
            val path = CompactionText.outputPath(message.result)
            if (path != null) path.takeIf { fileAccess.exists(it) && fileAccess.isFile(it) } else {
                val hash = MessageDigest.getInstance("SHA-256").digest(message.result.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                val stored = pathHomeResolver.aharouRoot() + "/tool-output/soft-trim-$hash.txt"
                if (!fileAccess.exists(stored)) fileAccess.writeFile(stored, message.result, overwrite = false)
                stored
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "Cannot archive tool output for soft trim: ${e.message}")
            null
        }
    }

    /**
     * 分级裁剪的「键集合」：三级各记一个切点（该级只裁它之前的迭代）。同一份历史重复规划得到同样的切点，
     * 历史只在尾部增长时早消息的裁剪结果逐字节不变——供应商的前缀缓存仍能命中。
     */
    internal data class TrimKeys(val reasoning: String? = null, val shrink: String? = null, val skeleton: String? = null) {
        val isEmpty: Boolean get() = reasoning == null && shrink == null && skeleton == null
    }

    /** 一次分级裁剪：键集合 + 应用后的投影（历史本体与落库内容都没动）。 */
    internal class TrimPlan(val keys: TrimKeys, val messages: List<AgentMessage>, val before: Int, val after: Int, val steps: List<String>) {
        val changed: Boolean get() = !keys.isEmpty
    }

    /** 迭代 = 一条助手消息 + 紧随其后的工具结果；键取助手消息 id（历史只在尾部增长时保持稳定）。 */
    private fun trimIterations(messages: List<AgentMessage>): Pair<List<String>, IntArray> {
        val keys = ArrayList<String>()
        val ordinals = IntArray(messages.size) { -1 }
        var index = 0
        while (index < messages.size) {
            if (messages[index] is AgentMessage.AssistantMessage) {
                keys += messages[index].id.ifBlank { "idx:$index" }
                var next = index
                while (next < messages.size && (next == index || messages[next] is AgentMessage.ToolResultMessage)) ordinals[next++] = keys.lastIndex
                index = next
            } else index++
        }
        return keys to ordinals
    }

    private fun keyAt(count: Int, keys: List<String>): String? = when {
        count <= 0 -> null
        count >= keys.size -> keys.last()
        else -> keys[count]
    }

    /** 切点 → 该级影响到的迭代数；键已不在列表里就视为该级失效。 */
    private fun cutBefore(key: String?, keys: List<String>): Int = if (key == null) 0 else keys.indexOf(key) + 1

    /**
     * 键集合落到现有消息结构上：L1 只清 [AgentMessage.AssistantMessage.reasoning]（DeepSeek 的 reasoning_content
     * 字段只要存在就够，空串照发；带签名或 thinking 快照的 Anthropic/Gemini 跳过，否则签名对不上会 400）；
     * L2/L3 只写 modelResult 与工具参数，`result` 与落库内容一字不改；用户消息从不裁。
     */
    private fun applyTrim(messages: List<AgentMessage>, before: TrimKeys, ordinals: IntArray, keys: List<String>): List<AgentMessage> {
        val reasoningCut = cutBefore(before.reasoning, keys)
        val shrinkCut = cutBefore(before.shrink, keys)
        val skeletonCut = cutBefore(before.skeleton, keys)
        if (reasoningCut == 0 && shrinkCut == 0 && skeletonCut == 0) return messages
        var changed = false
        val trimmed = messages.mapIndexed { index, message ->
            val ordinal = ordinals[index]
            val limit = if (ordinal < 0) 0 else if (ordinal < skeletonCut) TRIM_SKELETON_CHARS else if (ordinal < shrinkCut) TRIM_SHRINK_CHARS else 0
            val result = when (message) {
                is AgentMessage.AssistantMessage -> {
                    val signed = message.signature.isNotEmpty() || message.thinkingBlocksJson.isNotEmpty()
                    val clear = ordinal in 0 until reasoningCut && message.reasoning.isNotEmpty() && !signed
                    val calls = if (limit > 0) shortenCalls(message.toolCalls, limit) else message.toolCalls
                    if (calls === message.toolCalls) {
                        if (clear) message.copy(reasoning = "") else message
                    } else message.copy(toolCalls = calls, reasoning = if (clear) "" else message.reasoning)
                }
                is AgentMessage.ToolResultMessage -> if (limit > 0) shrinkResult(message, limit) else message
                is AgentMessage.UserMessage -> message
            }
            if (result !== message) changed = true
            result
        }
        return if (changed) trimmed else messages
    }

    /**
     * 分级裁剪：按成本从低到高逐级试（L1 清较旧轮次的 reasoning → L2 大工具结果/长参数落盘换引用 → L3 骨架化），
     * 每级重新估算、取「刚好够用」的最小前缀就停；最新 [keepRecent] 轮迭代不动（正在进行的工具交换不能被裁）。
     */
    fun planTrim(
        messages: List<AgentMessage>, target: Int, sendsImages: Boolean,
        systemPrompt: String, tools: List<AgentTool>, keepRecent: Int
    ): TrimPlan {
        val (keys, ordinals) = trimIterations(messages)
        fun measure(list: List<AgentMessage>) = CompactionText.estimateRequest(systemPrompt, tools, projectImagesForEstimate(list, sendsImages))
        val before = measure(messages)
        // 没有可裁内容（无 reasoning、无长输出/长参数）时不必逐级测量：纯文本长会话每轮都会走到这里。
        fun longText(text: String) = text.length > TRIM_SHRINK_CHARS
        val trimmable = messages.any {
            when (it) {
                is AgentMessage.AssistantMessage -> it.reasoning.isNotEmpty() || it.toolCalls.any { call -> call.arguments.values.any { v -> longText((v as? JsonPrimitive)?.content.orEmpty()) } }
                is AgentMessage.ToolResultMessage -> longText(it.modelResult ?: it.result)
                is AgentMessage.UserMessage -> false
            }
        }
        if (keys.isEmpty() || before <= target || !trimmable) return TrimPlan(TrimKeys(), messages, before, before, emptyList())
        val memo = HashMap<String, Pair<List<AgentMessage>, Int>>()
        fun at(extent: IntArray): Pair<List<AgentMessage>, Int> = memo.getOrPut(extent.joinToString(",")) {
            val applied = applyTrim(messages, TrimKeys(keyAt(extent[0], keys), keyAt(extent[1], keys), keyAt(extent[2], keys)), ordinals, keys)
            applied to measure(applied)
        }
        val older = (keys.size - keepRecent).coerceIn(0, keys.size)
        val maxExtent = intArrayOf((keys.size - 1).coerceAtLeast(0), older, older)
        var extent = IntArray(3)
        var size = before
        val steps = ArrayList<String>()
        for (level in 0..2) {
            if (maxExtent[level] <= 0) continue
            val full = extent.copyOf().also { it[level] = maxExtent[level] }
            val fullSize = at(full).second
            // 这一级在这份历史上无可裁内容（没有 reasoning / 没有大输出）：跳过，不记步骤。
            if (fullSize >= size) continue
            if (fullSize > target) {
                extent = full
                steps += "L${level + 1}(n=${maxExtent[level]}):${size - fullSize}"
                size = fullSize
                continue
            }
            var low = 1
            var high = maxExtent[level]
            while (low < high) {
                val mid = (low + high) / 2
                if (at(extent.copyOf().also { it[level] = mid }).second <= target) high = mid else low = mid + 1
            }
            extent = extent.copyOf().also { it[level] = low }
            val now = at(extent).second
            steps += "L${level + 1}(n=$low):${size - now}"
            size = now
            break
        }
        val (applied, after) = at(extent)
        return TrimPlan(TrimKeys(keyAt(extent[0], keys), keyAt(extent[1], keys), keyAt(extent[2], keys)), applied, before, after, steps)
    }

    /** L2/L3 的单条工具结果：优先指向工具自己落盘的原件，否则补写到 trim 目录；都落不下就折断并注明。 */
    private fun shrinkResult(message: AgentMessage.ToolResultMessage, limit: Int): AgentMessage.ToolResultMessage {
        val current = message.modelResult
            ?: com.aharou.feature.agent.domain.tool.modelToolResultText(message.toolName, message.result)
            ?: message.result
        if (current.length <= limit || current.startsWith(TRIM_PREFIX)) return message
        val path = storeOriginalToolOutput(message)
        val where = if (path != null) "full output: $path" else "full output stays in the session history"
        val text = if (limit > TRIM_SKELETON_CHARS) {
            if (path != null) "$TRIM_PREFIX ${message.toolName} output (${current.length} chars) offloaded to $path; use readFile to read it"
            else current.take(limit / 2) + "\n$TRIM_PREFIX ${message.toolName} output shortened (${current.length} chars); " + where
        } else {
            val first = current.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(TRIM_SKELETON_CHARS).orEmpty()
            "$TRIM_PREFIX ${message.toolName}: $first\n[shortened for context; $where]"
        }
        return if (text.length < current.length) message.copy(modelResult = text) else message
    }

    /** 大参数落盘换占位：回放的是已经执行过的调用，占位文案明说不是原值，模型不会拿它当参数复用。 */
    private fun shortenCalls(calls: List<ToolCall>, limit: Int): List<ToolCall> {
        if (calls.isEmpty()) return calls
        var changed = false
        val shortened = calls.map { call ->
            var touched = false
            val arguments = call.arguments.mapValues { (_, value) ->
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (text == null || text.length <= limit || text.startsWith(TRIM_PREFIX)) return@mapValues value
                val path = archiveTrimText(text, call.name) ?: return@mapValues value
                val notice = "$TRIM_PREFIX ${call.name} argument (${text.length} chars) offloaded to $path. NOT the real value - read the file if needed"
                if (notice.length >= text.length) return@mapValues value
                touched = true
                JsonPrimitive(notice)
            }
            if (touched) {
                changed = true
                call.copy(arguments = arguments)
            } else call
        }
        return if (changed) shortened else calls
    }

    /** 长文本落盘，返回容器内路径；落不下返回 null（该参数保持原样）。 */
    private fun archiveTrimText(text: String, tag: String): String? = try {
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val stored = pathHomeResolver.aharouRoot() + "/tool-output/trim-" + tag.filter { it.isLetterOrDigit() } + "-$hash.txt"
        if (!fileAccess.exists(stored)) fileAccess.writeFile(stored, text, overwrite = false)
        stored
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FileLogger.w(TAG, "Cannot archive trimmed tool argument: ${e.message}")
        null
    }
}

/** 默认保留的图片张数。 */
internal const val DEFAULT_CONTEXT_IMAGES = 4
private const val IMAGE_OMITTED_NOTE = "\n(较早的截图已省略)"

/**
 * 送模型前限制图片数量：只保留最新 [maxImages] **张**图参与本次请求，更早的图片换成一行占位说明，
 * 消息里的文字仍保留。作用的是「送给模型的副本」，历史本体不动（[dropImages] 返回新对象）。
 * 限额按累计张数而不是消息条数：单条消息可带多张图（见 ImageTools / McpTool），按条数会在
 * 「一条带 8 张图」时把 8 张全发出去。取舍仍以整条消息为单位（不会只发半条消息里的图），
 * 但停在哪一条由累计张数决定；最新的那条带图消息无条件保留，否则刚发的截图会被自己裁掉。
 * 参考 nanomuse vision.py 的 keep_newest_images()：带图消息多为影子屏/浏览器截图，
 * 每轮工具循环都会重发整段历史，不设预算会反复传输全部截图。
 */
internal fun keepNewestImages(messages: List<AgentMessage>, maxImages: Int = DEFAULT_CONTEXT_IMAGES): List<AgentMessage> {
    if (maxImages <= 0) return messages
    var budget = maxImages
    val keep = HashSet<Int>()
    for (index in messages.indices.reversed()) {
        val count = messages[index].imageCount()
        if (count == 0) continue
        if (keep.isNotEmpty() && count > budget) break
        keep.add(index)
        budget -= count
    }
    if (keep.isEmpty() || keep.size == messages.count { it.imageCount() > 0 }) return messages
    return messages.mapIndexed { index, message ->
        if (index in keep || message.imageCount() == 0) message else message.dropImages()
    }
}

/**
 * 估算侧的图片投影：与发送侧共用 [keepNewestImages]（同一套裁剪逻辑，两边各写一份迟早漂移成
 * 「估的比发的小」）。不支持图片的模型按「一张都不发」算，补的占位文案也顺带对上真实请求里的说明。
 */
internal fun projectImagesForEstimate(messages: List<AgentMessage>, sendsImages: Boolean): List<AgentMessage> =
    if (sendsImages) keepNewestImages(messages)
    else messages.map { message -> if (message.imageCount() > 0) message.dropImages() else message }

private fun AgentMessage.imageCount(): Int = when (this) {
    is AgentMessage.UserMessage -> images.size
    is AgentMessage.AssistantMessage -> images.size
    is AgentMessage.ToolResultMessage -> images.size
}

/** 剥掉图片并补一行占位说明。工具结果的占位接在喂模型的 [AgentMessage.ToolResultMessage.modelResult] 上，不动 result。 */
private fun AgentMessage.dropImages(): AgentMessage = when (this) {
    is AgentMessage.UserMessage -> copy(images = emptyList(), content = content + IMAGE_OMITTED_NOTE)
    is AgentMessage.AssistantMessage -> copy(images = emptyList(), content = content + IMAGE_OMITTED_NOTE)
    is AgentMessage.ToolResultMessage -> copy(
        images = emptyList(),
        modelResult = (modelResult
            ?: com.aharou.feature.agent.domain.tool.modelToolResultText(toolName, result)
            ?: result) + IMAGE_OMITTED_NOTE
    )
}
