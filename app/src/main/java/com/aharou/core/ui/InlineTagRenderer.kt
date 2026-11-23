/*
 * 行为参考 AAswordman/Operit（ui/common/markdown/XmlRenderPluginRegistry.kt 与
 * ui/common/markdown/CustomXmlRenderer.kt，LGPL-3.0）。本文件随 Aharou 以 GPL-3.0 分发。
 *
 * 与上游的区别：上游由插件在运行期 `registerXmlRenderPlugin` 把 tag → JS 钩子登记进注册表；
 * 宿主侧注册来源改为清单声明（`contributes.hooks` 的 `event: "chat.tag"`），
 * 切分由 [InlineTagSegmenter] 完成，本层只负责「调用钩子 → 拿到结果 → 渲染或降级」。
 */

package com.aharou.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.feature.plugin.dsl.ComposeDslParser
import com.aharou.feature.plugin.dsl.ComposeDslRenderResult
import com.aharou.feature.plugin.dsl.RenderComposeDsl
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject

/** 一个标签被哪个插件接管；来自 `PluginManifest.chatTags`，与运行时实例一一对应。 */
data class InlineTagBinding(
    val tag: String,
    val pluginId: String,
    val pluginVersion: String,
    /** 插件 runtime 类型（javascript / python），接线方据此决定走哪条进程通道。 */
    val runtimeType: String,
    val handler: String,
    val heightDp: Int? = null,
    val maxHeightDp: Int? = null
)

/** 当前生效的内联标签表。同一标签被多个插件声明时先到先得，与安装顺序一致。 */
class InlineTagRegistry private constructor(
    private val bindings: Map<String, InlineTagBinding>
) {
    val tags: Set<String> get() = bindings.keys

    val isEmpty: Boolean get() = bindings.isEmpty()

    operator fun get(tag: String): InlineTagBinding? = bindings[tag.trim().lowercase()]

    companion object {
        val EMPTY = InlineTagRegistry(emptyMap())

        fun of(bindings: List<InlineTagBinding>): InlineTagRegistry {
            if (bindings.isEmpty()) return EMPTY
            val map = LinkedHashMap<String, InlineTagBinding>()
            bindings.forEach { map.putIfAbsent(it.tag.trim().lowercase(), it) }
            return InlineTagRegistry(map)
        }
    }
}

/** 插件钩子返回的内容；三种形态对应 Operit 里 XmlRenderResult 的同名分支。 */
sealed interface InlineTagContent {
    data class ComposeDsl(val result: ComposeDslRenderResult) : InlineTagContent

    /** 纯文本；渲染方按 markdown 显示。 */
    data class Markdown(val text: String) : InlineTagContent

    /** 没有 handler，或 handler 不接管 / 调用失败：降级为占位 + 原文。 */
    data object Unavailable : InlineTagContent
}

/**
 * 与插件进程的接口。后续接线负责把 [render] 映射到
 * `PluginRuntimeManager.invoke(pluginId, version, runtime, handler, args, method = "hook.invoke")`：
 *
 * - args = `{"tagName": <标签名>, "xmlContent": <含首尾标签的原文>, "attributes": {…}}`（JsonObject），
 *   与上游 ToolPkgXmlRenderBridgePlugin 传给 JS 的 payload 对齐（上游只有前两个字段，attributes 是新增便利项）；
 * - 返回值（JsonElement）直接交给 [InlineTagResultParser]：`{composeDsl: {tree, state, memo}}`
 *   或 `{text}` / 纯字符串；
 * - Compose DSL 里的 actionId 通过 [dispatch] 回传，args = `{"tagName": …, "actionId": …, "value": …}`。
 *
 * 实现方必须自己保证插件进程保活（面板打开时是 acquireLease，这里同样需要），并设置超时；
 * 失败一律返回 [InlineTagContent.Unavailable]，不要把异常抛给渲染层。
 */
interface InlineTagHandler {
    suspend fun render(
        binding: InlineTagBinding,
        attributes: Map<String, String>,
        innerXml: String
    ): InlineTagContent

    suspend fun dispatch(
        binding: InlineTagBinding,
        actionId: String,
        value: Any?
    ): InlineTagContent? = null
}

/** 当前生效的标签表；由聊天面板按已安装插件计算后注入。 */
val LocalInlineTagRegistry = staticCompositionLocalOf { InlineTagRegistry.EMPTY }

/** 插件钩子实现；未接线时为 null，标签块降级为占位 + 原文。 */
val LocalInlineTagHandler = staticCompositionLocalOf<InlineTagHandler?> { null }

/** 内联组件的默认最大高度：单个组件不能把整条消息撑得看不见上下文。 */
const val DEFAULT_INLINE_TAG_MAX_HEIGHT_DP = 320

/** 把插件钩子返回值翻译成渲染层能用的三种形态；无法识别时按不接管处理。 */
object InlineTagResultParser {
    fun parse(raw: Any?): InlineTagContent = when (raw) {
        null -> InlineTagContent.Unavailable
        is String -> parseString(raw)
        // 运行时 invoke 返回 JsonElement，直接接住，接线方不必自己转一道。
        is JsonElement -> parseString(if (raw is JsonPrimitive) raw.content else raw.toString())
        is JSONObject -> parseObject(raw) ?: InlineTagContent.Unavailable
        is Map<*, *> -> runCatching { parseObject(JSONObject(raw)) }.getOrNull()
            ?: InlineTagContent.Unavailable
        else -> InlineTagContent.Unavailable
    }

    private fun parseString(raw: String): InlineTagContent {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) return InlineTagContent.Unavailable
        if (!trimmed.startsWith("{")) return InlineTagContent.Markdown(trimmed)
        // 钩子可以返回「再包一层 JSON 字符串」的对象，与上游 decodeHookResult 的口径一致。
        val obj = runCatching { JSONObject(trimmed) }.getOrNull() ?: return InlineTagContent.Markdown(trimmed)
        return parseObject(obj) ?: InlineTagContent.Unavailable
    }

    private fun parseObject(obj: JSONObject): InlineTagContent? {
        if (obj.has("handled") && !obj.isNull("handled") && !obj.optBoolean("handled", true)) {
            return InlineTagContent.Unavailable
        }
        val composeDsl = obj.opt("composeDsl")
        if (composeDsl != null && !(composeDsl is String && composeDsl.isBlank())) {
            ComposeDslParser.parseRenderResult(composeDsl)?.let { return InlineTagContent.ComposeDsl(it) }
        }
        val text = obj.optString("text").ifBlank { obj.optString("content") }.trim()
        return if (text.isNotEmpty()) InlineTagContent.Markdown(text) else null
    }
}

/**
 * 渲染一个标签块。无 handler、handler 不接管、调用失败都落到同一降级路径：小字占位 + 原文，
 * 保证消息本身不消失。高度受 [InlineTagBinding] 约束，超出后组件内部滚动。
 */
@Composable
fun InlineTagBlock(
    segment: InlineTagSegment.Tag,
    binding: InlineTagBinding,
    handler: InlineTagHandler?,
    textContent: @Composable (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var content by remember(segment.raw, binding, handler) { mutableStateOf<InlineTagContent?>(null) }
    LaunchedEffect(segment.raw, binding, handler) {
        content = if (handler == null) {
            InlineTagContent.Unavailable
        } else {
            runCatching { handler.render(binding, segment.attributes, segment.raw) }
                .getOrElse { InlineTagContent.Unavailable }
        }
    }
    when (val current = content) {
        null -> textContent(segment.raw)
        is InlineTagContent.ComposeDsl -> InlineTagDslContent(
            result = current.result,
            binding = binding,
            handler = handler,
            segmentKey = segment.raw,
            modifier = modifier,
            onReplaced = { content = it }
        )
        is InlineTagContent.Markdown -> textContent(current.text)
        InlineTagContent.Unavailable -> InlineTagFallback(segment, textContent, modifier)
    }
}

@Composable
private fun InlineTagDslContent(
    result: ComposeDslRenderResult,
    binding: InlineTagBinding,
    handler: InlineTagHandler?,
    segmentKey: String,
    modifier: Modifier,
    onReplaced: (InlineTagContent) -> Unit
) {
    val scope = rememberCoroutineScope()
    val currentHandler by rememberUpdatedState(handler)
    val onAction: (String, Any?) -> Unit = remember(binding) {
        { actionId, value ->
            val target = currentHandler
            if (target != null) {
                scope.launch {
                    val next = runCatching { target.dispatch(binding, actionId, value) }.getOrNull()
                    if (next != null) onReplaced(next)
                }
            }
        }
    }
    val maxHeight = (binding.maxHeightDp ?: DEFAULT_INLINE_TAG_MAX_HEIGHT_DP).dp
    val fixedHeight = binding.heightDp?.dp
    // 根节点自己就是懒加载列表时不能再套 verticalScroll：滚动容器嵌滚动容器会在测量时报无限高度冲突。
    val rootScrollable = isScrollableDslRoot(result.tree.type)
    val scrollState = rememberScrollState()
    val sizeModifier = if (fixedHeight != null) Modifier.height(fixedHeight) else Modifier.heightIn(max = maxHeight)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(sizeModifier)
            .clip(RoundedCornerShape(Radius.mdLarge))
            .clipToBounds()
            .then(if (rootScrollable) Modifier else Modifier.verticalScroll(scrollState))
    ) {
        RenderComposeDsl(
            tree = result.tree,
            state = result.state,
            onAction = onAction,
            modifier = Modifier.fillMaxWidth(),
            scrollStateKey = "inline_tag:${binding.pluginId}:${binding.tag}:${segmentKey.hashCode()}"
        )
    }
}

@Composable
private fun InlineTagFallback(
    segment: InlineTagSegment.Tag,
    textContent: @Composable (String) -> Unit,
    modifier: Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
    ) {
        Text(
            text = stringResource(R.string.plugin_chat_tag_unavailable),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs)
        )
        textContent(segment.raw)
    }
}

private val SCROLLABLE_DSL_ROOTS = setOf(
    "lazycolumn", "lazyrow", "lazyverticalgrid", "lazyhorizontalgrid", "verticalpager", "horizontalpager"
)

private fun isScrollableDslRoot(type: String): Boolean =
    type.filter { it.isLetterOrDigit() }.lowercase() in SCROLLABLE_DSL_ROOTS
