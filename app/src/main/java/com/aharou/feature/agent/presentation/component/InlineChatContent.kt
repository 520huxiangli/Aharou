package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.core.ui.InlineTagBinding
import com.aharou.core.ui.InlineTagBlock
import com.aharou.core.ui.InlineTagRegistry
import com.aharou.core.ui.InlineTagSegment
import com.aharou.core.ui.InlineTagSegmenter
import com.aharou.core.ui.LocalInlineTagHandler
import com.aharou.core.ui.LocalInlineTagRegistry
import com.aharou.feature.plugin.domain.PluginManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 从已启用的插件清单里取聊天内联标签声明，组装成当前生效的标签表。
 *
 * 只认 `isActive`（启用、清单可读、状态正常）：停用插件后它的标签立刻退回原文，
 * 不需要卸载或重启。
 */
@Composable
internal fun rememberChatInlineTagRegistry(): InlineTagRegistry {
    val context = LocalContext.current.applicationContext
    val pluginManager = remember(context) {
        runCatching {
            EntryPointAccessors.fromApplication(context, ChatInlineTagEntryPoint::class.java).pluginManager()
        }.getOrNull()
    } ?: return InlineTagRegistry.EMPTY
    val plugins by pluginManager.plugins.collectAsStateWithLifecycle()
    return remember(plugins) {
        InlineTagRegistry.of(
            plugins.filter { it.isActive }.flatMap { plugin ->
                val manifest = plugin.manifest ?: return@flatMap emptyList<InlineTagBinding>()
                val runtimeType = manifest.runtime?.type.orEmpty()
                manifest.chatTags.map { chatTag ->
                    InlineTagBinding(
                        tag = chatTag.tag,
                        pluginId = manifest.id,
                        pluginVersion = manifest.version.toString(),
                        runtimeType = runtimeType,
                        handler = chatTag.handler,
                        heightDp = chatTag.heightDp,
                        maxHeightDp = chatTag.maxHeightDp
                    )
                }
            }
        )
    }
}

/** Composable 取不到注入单例（没有 ViewModel 入口），只能走 EntryPoint。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ChatInlineTagEntryPoint {
    fun pluginManager(): PluginManager
}

/**
 * 聊天气泡正文的渲染分支：没有已注册标签时完全等价于 [MarkdownContent]，
 * 有标签时按切分结果分别走 markdown 与 [InlineTagBlock]。
 *
 * 这里只做分发，不碰 markdown 解析本身——[MarkdownContent] 内部的缓存、流式尾窗、
 * 超长护栏与懒滚动都保持原样，每个文本段仍然拿同一个 [cache]。
 */
@Composable
internal fun InlineChatContent(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    cache: MarkdownRenderCache? = null
) {
    val registry = LocalInlineTagRegistry.current
    val handler = LocalInlineTagHandler.current
    val segments = remember(text, registry) {
        if (registry.isEmpty) emptyList() else InlineTagSegmenter.segment(text, registry.tags)
    }
    if (segments.isEmpty() || (segments.size == 1 && segments[0] is InlineTagSegment.Text)) {
        MarkdownContent(text = text, color = color, modifier = modifier, cache = cache)
        return
    }
    Column(modifier = modifier) {
        segments.forEach { segment ->
            when (segment) {
                is InlineTagSegment.Text -> MarkdownContent(
                    text = segment.text,
                    color = color,
                    modifier = Modifier.fillMaxWidth(),
                    cache = cache
                )
                is InlineTagSegment.Tag -> {
                    val binding = registry[segment.tag]
                    if (binding == null) {
                        // 标签在切分后失效（插件被停用）时不要把内容吞掉。
                        MarkdownContent(
                            text = segment.raw,
                            color = color,
                            modifier = Modifier.fillMaxWidth(),
                            cache = cache
                        )
                    } else {
                        InlineTagBlock(
                            segment = segment,
                            binding = binding,
                            handler = handler,
                            textContent = { value ->
                                MarkdownContent(
                                    text = value,
                                    color = color,
                                    modifier = Modifier.fillMaxWidth(),
                                    cache = cache
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}
