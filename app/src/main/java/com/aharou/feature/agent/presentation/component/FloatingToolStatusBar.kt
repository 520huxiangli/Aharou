package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.R
import com.aharou.feature.agent.presentation.AgentUIMessage
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.X
import kotlinx.coroutines.delay

// ── 小屏幕配色（对齐 原版 的迷你终端缩略图）──
private val MiniScreenBg = Color(0xFF1A1A1E)
private val MiniScreenGreen = Color(0xFF34C759)
private val MiniScreenRed = Color(0xFFFF6666)
private val MiniScreenBorder = Color(0x33808080)
private val MiniStopRed = Color(0xFFE53935)

private val MiniMono = FontFamily(Font(R.font.jetbrains_mono_nl))

/**
 * 「小屏幕」底部浮动工具条（Aharou 移植版，对齐 原版 的 FloatingToolStatusBar）。
 *
 * 结构 = [状态条 38dp] + [迷你终端缩略图 100×65dp 悬在条子上方左侧]：
 *  - 缩略图：`$ 命令` + 最后 12 行输出（实时滚动，等宽绿字）——即"看得见电脑在干什么"；
 *  - 状态条：工具名 + 运行状态 + ‹ n/m › 翻页浏览这一串工具调用 + 运行中可停止；
 *  - 点缩略图 → 打开 Aharou Computer 详情面板（onOpenDetail）。
 */
@Composable
internal fun FloatingToolStatusBar(
    toolMessages: List<AgentUIMessage>,
    liveOutputFor: (String) -> String?,
    onOpenDetail: (String) -> Unit,
    onStop: () -> Unit,
    browserPool: com.aharou.feature.browser.BrowserTabPool? = null,
    vdCapture: (suspend (Int) -> android.graphics.Bitmap?)? = null,
    modifier: Modifier = Modifier,
) {
    if (toolMessages.isEmpty()) return
    val lastIndex = toolMessages.lastIndex
    var currentIndex by remember(toolMessages.size) { mutableStateOf(lastIndex.coerceAtLeast(0)) }

    // 自动跟随最新工具：当前显示的不是"活跃"工具时，跳回最新。
    LaunchedEffect(lastIndex) {
        val block = toolMessages.getOrNull(currentIndex) ?: return@LaunchedEffect
        val isCurrentActive = liveOutputFor(block.id) != null
        if (!isCurrentActive) currentIndex = lastIndex.coerceAtLeast(0)
    }
    val block = toolMessages.getOrNull(currentIndex.coerceAtLeast(0)) ?: return

    val live = liveOutputFor(block.id)
    val isRunning = live != null
    val isError = block.isError

    val thumbnailWidth = 100.dp
    val thumbnailHeight = 65.dp
    val barHeight = 38.dp
    val thumbnailOverhang = thumbnailHeight - barHeight
    val thumbnailInset = 10.dp
    val barStartPadding = thumbnailWidth + thumbnailInset + 8.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = thumbnailOverhang),
    ) {
        // ── Layer 1：状态条 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight)
                .align(Alignment.BottomCenter)
                .shadow(8.dp, RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp))
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                .padding(start = barStartPadding, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isRunning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    color = MiniScreenGreen,
                    strokeWidth = 1.5.dp,
                )
            } else {
                Icon(
                    if (isError) FeatherIcons.X else FeatherIcons.Check,
                    contentDescription = null,
                    tint = if (isError) MiniScreenRed else MiniScreenGreen,
                    modifier = Modifier.size(15.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = block.toolName ?: "tool",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        isRunning -> "执行中…"
                        isError -> "失败"
                        else -> "完成"
                    },
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            // ── 翻页浏览这一串工具调用 ──
            if (toolMessages.size > 1) {
                IconButton(
                    onClick = { currentIndex = (currentIndex - 1).coerceAtLeast(0) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Text("‹", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    text = "${currentIndex.coerceIn(0, lastIndex) + 1}/${toolMessages.size}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(
                    onClick = { currentIndex = (currentIndex + 1).coerceAtMost(lastIndex) },
                    modifier = Modifier.size(28.dp),
                ) {
                    Text("›", fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // ── 运行中：停止键 ──
            if (isRunning) {
                IconButton(onClick = onStop, modifier = Modifier.size(28.dp)) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(MiniStopRed),
                    )
                }
            }
        }

        // ── Layer 2：小屏幕（迷你终端缩略图，悬在左上方）──
        ToolMiniScreenThumbnail(
            message = block,
            live = live,
            browserPool = browserPool,
            vdCapture = vdCapture,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = thumbnailInset),
            onClick = { onOpenDetail(block.id) },
        )
    }
}

/** 迷你终端小屏幕：`$ 命令` + 输出尾部（最多 12 行，实时滚动）。 */
@Composable
private fun ToolMiniScreenThumbnail(
    message: AgentUIMessage,
    live: String?,
    browserPool: com.aharou.feature.browser.BrowserTabPool? = null,
    vdCapture: (suspend (Int) -> android.graphics.Bitmap?)? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val thumbnailShape = RoundedCornerShape(8.dp)
    val isShell = message.toolName == "Bash" || message.toolName == "terminal"
    val isBrowser = message.toolName == "browser"
    val command = if (isShell) extractComputerShellCommand(message.toolArgs) else null

    val body = remember(message.id, message.content, live) {
        val raw = live ?: message.content
        val text = if (isShell || message.isError) {
            formatComputerOutput(raw, running = false)
        } else {
            raw
        }
        text.lines().takeLast(12).joinToString("\n")
    }

    Box(
        modifier = modifier
            .size(width = 100.dp, height = 65.dp)
            .shadow(10.dp, thumbnailShape)
            .clip(thumbnailShape)
            .background(MiniScreenBg)
            .border(0.5.dp, MiniScreenBorder, thumbnailShape)
            .clickable(onClick = onClick),
    ) {
        if (isBrowser) {
            // [Aharou] 浏览器工具：小屏幕 = 页面实时画面（对齐 原版 的 browser_use 缩略图）
            BrowserMiniScreen(pool = browserPool, running = live != null)
        } else if (message.toolName == "vscreen") {
            // [Aharou] 影子屏：小屏幕 = 虚拟屏实时画面（Agent 离屏操作，用户在这儿围观）
            VdMiniScreen(capture = vdCapture, running = live != null)
        } else {
        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) {
            Text(
                text = if (isShell && !command.isNullOrBlank()) "$ $command" else (message.toolName ?: "tool"),
                fontSize = 7.sp,
                lineHeight = 8.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = MiniMono,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (body.isNotBlank()) {
                Text(
                    text = body,
                    fontSize = 5.5.sp,
                    fontFamily = MiniMono,
                    color = if (message.isError) MiniScreenRed.copy(alpha = 0.9f)
                            else MiniScreenGreen.copy(alpha = 0.85f),
                    maxLines = 12,
                    lineHeight = 6.5.sp,
                )
            }
        }
        }
    }
}

/** 缩略图抓取上限（物理像素）：小屏幕只有 100×65dp，全尺寸帧纯属浪费。 */
private const val MINI_FRAME_MAX_W = 480
private const val MINI_FRAME_MAX_H = 480

/**
 * 浏览器小屏幕：实时抓浏览器池活动标签的页面画面（每 2s 一帧，
 * 对齐 原版 的 browser_use 缩略图机制）。无画面时显示占位文案。
 */
@Composable
private fun BrowserMiniScreen(
    pool: com.aharou.feature.browser.BrowserTabPool?,
    running: Boolean,
) {
    var frame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(pool, running) {
        var pendingRecycle: android.graphics.Bitmap? = null
        try {
            // running 为 false 直接不进循环；composable 离开时本 effect 取消，同样停在 delay 上
            while (running) {
                val active = pool?.activeManager
                if (active != null) {
                    val shot = runCatching {
                        active.captureLiveSnapshot(MINI_FRAME_MAX_W, MINI_FRAME_MAX_H)
                    }.getOrNull()
                    if (shot != null) {
                        // 上一帧已被顶下去（delay 期间完成重组），此时回收不会碰到在绘制的那张
                        pendingRecycle?.recycle()
                        pendingRecycle = frame
                        frame = shot
                    }
                }
                delay(2000)
            }
        } finally {
            pendingRecycle?.recycle()
        }
    }
    val bmp = frame
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = if (running) "抓取页面…" else "（暂无画面）",
                fontSize = 7.sp,
                fontFamily = MiniMono,
                color = MiniScreenGreen,
            )
        }
    }
}

/**
 * 影子屏小屏幕：实时抓虚拟屏画面（每 2s 一帧）。
 * Agent 在影子屏里静默操作（不占用户主屏）时，用户从这里围观它的操作页面。
 */
private const val MINI_VD_SAMPLE = 3

@Composable
private fun VdMiniScreen(
    capture: (suspend (Int) -> android.graphics.Bitmap?)?,
    running: Boolean,
) {
    var frame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(capture, running) {
        while (true) {
            val shot = capture?.let { runCatching { it(MINI_VD_SAMPLE) }.getOrNull() }
            if (shot != null) frame = shot
            delay(2000)
        }
    }
    val bmp = frame
    if (bmp != null) {
        androidx.compose.foundation.Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = if (running) "抓取画面…" else "影子屏未运行",
                fontSize = 7.sp,
                fontFamily = MiniMono,
                color = MiniScreenGreen,
            )
        }
    }
}
