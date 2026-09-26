package com.aicode.feature.agent.presentation.component

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.feature.browser.BrowserTabPool
import com.aicode.feature.browser.presentation.LocalBrowserOpener
import com.aicode.feature.terminal.presentation.component.LocalTerminalOpener
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.X
import kotlinx.coroutines.delay
import org.json.JSONObject

// ── 终端卡配色（与 Minis ChatToolDetailUI 的「黑色终端卡」对齐）──
private val ComputerCardBg = Color(0xFF141414)
private val ComputerCardBorder = Color(0xFF404040)
private val ComputerGreen = Color(0xFF34C759)
private val ComputerRed = Color(0xFFFF6666)
private val ComputerWhite = Color(0xFFE6E6E6)
private val ComputerBlue = Color(0xFF7CC4FF)

/** 等宽字体：与终端/浏览器一致（fusion 内置 JetBrains Mono）。 */
private val ComputerMono = FontFamily(Font(R.font.jetbrains_mono_nl))

/**
 * 「Minis Computer」工具详情面板（Aharou 移植版）。
 *
 * 对齐 Minis 的 Minis Computer（ToolDetailSheet）核心体验：
 *  - 点聊天里的工具行 → 掀开这个面板；
 *  - 上下滑动/翻页在会话的工具调用之间切换；
 *  - shell 命令 → 黑色终端卡（`$ 命令` + 输出，绿色等宽字）；
 *  - browser → 页面 URL + **实时快照**（每 2.5s 从浏览器池的活动标签抓帧）+ 结果文本；
 *  - 其余工具 → 参数 + 结果卡；
 *  - 右上角动作钮随工具类型变：终端预填 / 地球（打开浏览器）/ 复制结果。
 *
 * 裁剪说明（v1）：Minis 原版的「换模型重跑 / 文件提及 / 权限跳转」等周边未搬。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MinisComputerSheet(
    messages: List<AgentUIMessage>,
    initialMessageId: String,
    browserPool: BrowserTabPool?,
    onDismiss: () -> Unit,
) {
    val toolMessages = remember(messages) { messages.filter { it.toolName != null } }
    if (toolMessages.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val startIndex = toolMessages.indexOfFirst { it.id == initialMessageId }.coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = startIndex) { toolMessages.size }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val current = toolMessages.getOrNull(pagerState.currentPage)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f),
        ) {
            // ── 顶栏：✕ + "Minis Computer" + 动作钮 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onDismiss() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        FeatherIcons.X,
                        contentDescription = stringResource(R.string.common_close),
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Minis Computer",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                ComputerActionButton(message = current)
            }
            HorizontalDivider(thickness = 0.5.dp)

            if (toolMessages.size > 1) {
                Text(
                    text = "${pagerState.currentPage + 1}/${toolMessages.size}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 6.dp),
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                ToolComputerPage(
                    message = toolMessages[page],
                    browserPool = browserPool,
                )
            }
        }
    }
}

/** 右上角动作钮：随工具类型分发（终端预填 / 打开浏览器 / 复制结果）。 */
@Composable
private fun ComputerActionButton(message: AgentUIMessage?) {
    val clipboard = LocalClipboardManager.current
    val browserOpener = LocalBrowserOpener.current
    val terminalOpener = LocalTerminalOpener.current
    var copyDone by remember { mutableStateOf(false) }

    val shellCommand = message?.let {
        if (it.toolName == "Bash" || it.toolName == "terminal") extractComputerShellCommand(it.toolArgs) else null
    }
    val browserUrl = message?.let {
        if (it.toolName == "browser") extractComputerBrowserUrl(it.toolArgs) else null
    }

    when {
        shellCommand != null && terminalOpener != null -> {
            IconButton(onClick = { terminalOpener.invoke(shellCommand) }) {
                Icon(
                    FeatherIcons.Terminal,
                    contentDescription = stringResource(R.string.tool_action_open_terminal),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        browserUrl != null && browserOpener != null -> {
            IconButton(onClick = { browserOpener.invoke(browserUrl) }) {
                Icon(
                    FeatherIcons.Globe,
                    contentDescription = stringResource(R.string.tool_action_open_browser),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        else -> {
            IconButton(onClick = {
                clipboard.setText(AnnotatedString(message?.content.orEmpty()))
                copyDone = true
            }) {
                Icon(
                    if (copyDone) FeatherIcons.Check else FeatherIcons.Copy,
                    contentDescription = "复制结果",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolComputerPage(
    message: AgentUIMessage,
    browserPool: BrowserTabPool?,
) {
    val running = message.content.startsWith(SessionUseCase.PENDING_TOOL_MARKER) ||
        message.content.startsWith(SessionUseCase.LEGACY_PENDING_TOOL_MARKER)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = message.toolName ?: "tool",
                fontFamily = ComputerMono,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = when {
                    running -> "执行中…"
                    message.isError -> "失败"
                    else -> "完成"
                },
                fontSize = 11.sp,
                color = when {
                    running -> MaterialTheme.colorScheme.onSurfaceVariant
                    message.isError -> ComputerRed
                    else -> ComputerGreen
                },
            )
        }

        when {
            message.toolName == "Bash" || message.toolName == "terminal" -> {
                TerminalCard(
                    command = extractComputerShellCommand(message.toolArgs),
                    output = formatComputerOutput(message.content, running),
                    isError = message.isError,
                )
            }
            message.toolName == "browser" -> {
                BrowserCard(
                    url = extractComputerBrowserUrl(message.toolArgs),
                    output = formatComputerOutput(message.content, running),
                    running = running,
                    pool = browserPool,
                )
            }
            else -> {
                val args = message.toolArgs?.takeIf { it.isNotBlank() && it != "{}" }
                if (args != null) {
                    InfoCard(title = "参数", body = args, mono = true)
                }
                InfoCard(
                    title = "结果",
                    body = formatComputerOutput(message.content, running),
                    mono = false,
                    isError = message.isError,
                )
            }
        }

        if (message.toolName == "Bash" || message.toolName == "terminal" || message.toolName == "browser") {
            Text(
                text = "右上角可在终端 / 浏览器中打开",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 黑色终端卡：`$ 命令` + 输出（绿字等宽，对齐 Minis 的视觉）。 */
@Composable
private fun TerminalCard(command: String?, output: String, isError: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ComputerCardBg)
            .border(1.dp, ComputerCardBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!command.isNullOrBlank()) {
            Text(
                text = "$ $command",
                fontFamily = ComputerMono,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = ComputerWhite,
            )
            HorizontalDivider(thickness = 0.5.dp, color = ComputerCardBorder)
        }
        Text(
            text = output.ifBlank { "（无输出）" },
            fontFamily = ComputerMono,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = if (isError) ComputerRed else ComputerGreen,
        )
    }
}

/** 浏览器卡：URL + 实时快照（每 2.5s 抓帧）+ 结果文本。 */
@Composable
private fun BrowserCard(
    url: String?,
    output: String,
    running: Boolean,
    pool: BrowserTabPool?,
) {
    if (!url.isNullOrBlank()) {
        Text(
            text = url,
            fontFamily = ComputerMono,
            fontSize = 12.sp,
            color = ComputerBlue,
            maxLines = 2,
        )
    }
    BrowserLivePreview(pool = pool, running = running)
    Text(
        text = output.ifBlank { "（无输出）" },
        fontSize = 12.sp,
        lineHeight = 17.sp,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** 浏览器实况：轮询浏览器池的活动 WebView 抓快照（对齐 Minis 的 3s 轮询机制）。 */
@Composable
private fun BrowserLivePreview(pool: BrowserTabPool?, running: Boolean) {
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(pool, running) {
        while (true) {
            val active = pool?.activeManager
            if (active != null) {
                val shot = runCatching { active.captureLiveSnapshot() }.getOrNull()
                if (shot != null) frame = shot
            }
            delay(2500)
        }
    }
    val bmp = frame
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, ComputerCardBorder, RoundedCornerShape(10.dp)),
            contentScale = ContentScale.FillWidth,
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ComputerCardBg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (running) "正在抓取页面…" else "（暂无实时画面）",
                color = ComputerGreen,
                fontFamily = ComputerMono,
                fontSize = 12.sp,
            )
        }
    }
}

/** 通用信息卡（参数 / 结果）。 */
@Composable
private fun InfoCard(title: String, body: String, mono: Boolean, isError: Boolean = false) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = body.ifBlank { "（空）" },
            fontFamily = if (mono) ComputerMono else FontFamily.Default,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ── 小工具：从工具消息里提取命令 / URL / 整理输出 ──────────────────────────

private fun extractComputerShellCommand(toolArgs: String?): String? {
    val t = toolArgs ?: return null
    return runCatching {
        val obj = JSONObject(t)
        obj.optString("command").ifBlank { obj.optString("input") }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

private fun extractComputerBrowserUrl(toolArgs: String?): String? {
    val t = toolArgs ?: return null
    return runCatching { JSONObject(t).optString("url") }.getOrNull()?.takeIf { it.isNotBlank() }
}

/** 把工具结果（传输 JSON 或纯文本）整理成可读输出。 */
private fun formatComputerOutput(raw: String, running: Boolean): String {
    if (running) return "…"
    if (raw.isBlank()) return ""
    val t = raw.trim()
    if (t.startsWith("{")) {
        val parsed = runCatching {
            val obj = JSONObject(t)
            val status = obj.optString("status")
            val data = obj.optJSONObject("data")
            val text = when {
                data != null -> data.optString("result")
                    .ifBlank { data.optString("message") }
                    .ifBlank { data.optString("output") }
                else -> obj.optString("message")
            }
            if (text.isNotBlank()) {
                (if (status == "error") "Error: " else "") + text
            } else null
        }.getOrNull()
        if (parsed != null) return parsed
    }
    return raw
}
