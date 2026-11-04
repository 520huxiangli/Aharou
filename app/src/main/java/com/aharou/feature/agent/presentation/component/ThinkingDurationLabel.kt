package com.aharou.feature.agent.presentation.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.aharou.R
import kotlinx.coroutines.delay

/**
 * 思考耗时文案（折叠行那一段）。
 *
 * 计时状态（[TIMER_TICK_MS] 一跳）关在这里，计时值只在本节点内部被读：思考块展开后的大段
 * Markdown 子树不跟着每 100ms 重组一次。单列一个文件是为了不让 StatusBubbles.kt 继续长——
 * 那个文件贴着单文件行数上限。
 */
@Composable
internal fun ThinkingDurationLabel(
    /** 思考正文：只用于判定流式是否同一轮延续，不参与渲染。 */
    text: String,
    live: Boolean,
    showTimer: Boolean,
    /** 已完成思考的总耗时（毫秒）。非空且大于 0 时优先于正在走的计时。 */
    durationMs: Long?,
    timerStartMs: Long?,
    /** 思考所属会话：切会话时重新计时，否则会拿上一个会话的起点算出离谱的时长。 */
    sessionKey: String?
) {
    var timerStartMillis by rememberSaveable { mutableStateOf(0L) }
    var timerSeenChars by rememberSaveable { mutableStateOf(0) }
    var timerSeenHead by rememberSaveable { mutableStateOf(0) }
    var elapsedMillis by remember { mutableStateOf(0L) }
    val latestText by rememberUpdatedState(text)
    LaunchedEffect(showTimer, live, sessionKey, timerStartMs) {
        if (!showTimer || !live) return@LaunchedEffect
        if (timerStartMs != null) {
            timerStartMillis = timerStartMs
        } else if (!isStreamContinuation(latestText, timerSeenChars, timerSeenHead)) {
            // 同一轮延续就保留原起点：切页/回收后重挂不能把已走的计时清零
            timerStartMillis = System.currentTimeMillis()
        }
        while (true) {
            elapsedMillis = (System.currentTimeMillis() - timerStartMillis).coerceAtLeast(0L)
            timerSeenChars = latestText.length
            timerSeenHead = streamHeadFingerprint(latestText)
            delay(TIMER_TICK_MS)
        }
    }
    val effectiveDurationMs = durationMs?.takeIf { it > 0 } ?: elapsedMillis.takeIf { it > 0 }
    val label = when {
        live -> stringResource(R.string.chat_thinking_running_time, thinkingSecondsText(effectiveDurationMs))
        effectiveDurationMs != null -> stringResource(R.string.chat_thinking_done_time, thinkingSecondsText(effectiveDurationMs))
        else -> stringResource(R.string.chat_thinking_done)
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** 计时刷新间隔（毫秒）。 */
private const val TIMER_TICK_MS = 100L

/** 耗时秒数文本，保留一位小数；取不到时至少显示 0.1（刚起跳的瞬间别闪一下 0.0）。 */
private fun thinkingSecondsText(ms: Long?): String =
    ms?.let { String.format(java.util.Locale.US, "%.1f", (it / 1000.0).coerceAtLeast(0.1)) } ?: "0.1"
