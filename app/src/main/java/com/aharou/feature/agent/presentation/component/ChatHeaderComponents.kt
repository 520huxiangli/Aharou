package com.aharou.feature.agent.presentation.component

import android.os.Build
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.GlassSurface
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.onboarding.domain.OnboardingStep
import com.aharou.feature.onboarding.presentation.onboardingTarget
import compose.icons.FeatherIcons
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Archive
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.GitBranch
import compose.icons.feathericons.Globe
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.Menu
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Users

@Composable
internal fun ChatHeader(
    inputTokens: Int,
    outputTokens: Int,
    /** 当前会话 id：定时任务胶囊据此判断本会话是不是某个任务的目标。 */
    sessionId: String? = null,
    /** 当前会话标题：显示在左侧胶囊里，空会话回退成「新建会话」。 */
    sessionTitle: String? = null,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    /** 打开子代理工作流（顶栏常驻入口，不再走侧栏 Tab）。 */
    onOpenWorkflow: () -> Unit = {},
    onNavigateToTerminal: () -> Unit,
    onNavigateToGit: () -> Unit,
    onNavigateToBrowser: () -> Unit = {},
    onNavigateToSandbox: () -> Unit = {},
    onNavigateToShared: () -> Unit = {},
    currentMode: AgentMode,
    onToggleMode: (AgentMode) -> Unit,
    connectionState: com.aharou.feature.agent.domain.container.ConnectionState? = null,
    terminalActive: Boolean = false,
    gitActive: Boolean = false,
    browserActive: Boolean = false
) {
    // 顶栏工具收纳：Git / 终端 / 浏览器 收进一个按钮，展开时在原位覆盖成那三个入口，
    // 替出来的格子给智能托管。展开态只改这一段，不弹浮层也不撑开新行。
    var toolsExpanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                // 左侧玻璃胶囊：会话标题 + 新建。汉堡键并入这里——点胶囊就是开侧栏，
                // 顶栏不再单独摆一个菜单钮。
                GlassSurface(
                    modifier = Modifier.weight(1f, fill = false),
                    shape = RoundedCornerShape(Radius.pill),
                    shadowElevation = 4.dp
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            modifier = Modifier
                                .onboardingTarget(OnboardingStep.OPEN_SIDEBAR)
                                .clip(RoundedCornerShape(Radius.pill))
                                .clickable(onClick = onOpenDrawer)
                                .padding(start = Spacing.sm, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            Icon(
                                FeatherIcons.Menu,
                                contentDescription = stringResource(R.string.chat_open_sidebar),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = sessionTitle?.takeIf { it.isNotBlank() }
                                    ?: stringResource(R.string.chat_new_session),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Icon(
                                FeatherIcons.ChevronDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        IconButton(onClick = onNewChat, modifier = Modifier.size(36.dp)) {
                            Icon(
                                FeatherIcons.Plus,
                                contentDescription = stringResource(R.string.chat_new_session),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp))
                        }
                    }
                }

                // 右侧玻璃胶囊：工作流常驻 + 工具组（展开时原位换成 Git / 终端 / 浏览器）
                GlassSurface(
                    shape = RoundedCornerShape(Radius.pill),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        WorkbenchIconButton(
                            icon = FeatherIcons.Users,
                            contentDescription = stringResource(R.string.drawer_tab_workflow),
                            active = false,
                            onClick = onOpenWorkflow
                        )
                        if (toolsExpanded) {
                            // 展开态：只有工具三入口 + 收起，其余一律让位
                            WorkbenchIconButton(
                                icon = FeatherIcons.GitBranch,
                                contentDescription = stringResource(R.string.chat_open_git),
                                active = gitActive,
                                onClick = onNavigateToGit
                            )
                            WorkbenchIconButton(
                                icon = FeatherIcons.Terminal,
                                contentDescription = stringResource(R.string.chat_open_terminal),
                                active = terminalActive,
                                onClick = onNavigateToTerminal
                            )
                            WorkbenchIconButton(
                                icon = FeatherIcons.Globe,
                                contentDescription = stringResource(R.string.chat_open_browser),
                                active = browserActive,
                                onClick = onNavigateToBrowser
                            )
                            IconButton(onClick = { toolsExpanded = false }) {
                                Icon(
                                    FeatherIcons.ChevronRight,
                                    contentDescription = stringResource(R.string.chat_tools_collapse),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            // 智能托管常驻顶栏：AI 空闲时自动判断任务是否完成并续写下一步
                            sessionId?.takeIf { it.isNotBlank() }?.let { StewardEntryButton(sessionId = it) }
                            WorkbenchIconButton(
                                icon = FeatherIcons.Folder,
                                contentDescription = stringResource(R.string.chat_open_sandbox),
                                active = false,
                                onClick = onNavigateToSandbox
                            )
                            WorkbenchIconButton(
                                icon = FeatherIcons.HardDrive,
                                contentDescription = stringResource(R.string.chat_open_shared),
                                active = false,
                                onClick = onNavigateToShared
                            )
                            IconButton(onClick = { toolsExpanded = true }) {
                                Icon(
                                    FeatherIcons.Archive,
                                    contentDescription = stringResource(R.string.chat_tools_expand),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            // 远程模式：左边 SSH 连接状态，右边 token 累计统计
            if (connectionState != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ConnectionIndicator(state = connectionState)
                    TokenStats(
                        inputTokens = inputTokens,
                        outputTokens = outputTokens
                    )
                }
            }
            // 本地容器会话没有连接状态，胶囊不能挂在上面那个远程分支里。
            ScheduledTaskPill(sessionId = sessionId)
        }
    }
}

/** 顶栏工作台入口按钮：大屏右栏开着对应内容时高亮，否则看不出点一下是开还是关。 */
@Composable
private fun WorkbenchIconButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit
) {
    // 38dp：顶栏胶囊里要横向排五个入口，默认 48dp 点击区会把胶囊挤满
    IconButton(onClick = onClick, modifier = Modifier.size(38.dp)) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .then(
                    if (active) {
                        Modifier
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun ConnectionIndicator(
    state: com.aharou.feature.agent.domain.container.ConnectionState
) {
    val (dotColor, text) = when (state) {
        com.aharou.feature.agent.domain.container.ConnectionState.CONNECTED ->
            MaterialTheme.colorScheme.primary to stringResource(R.string.chat_ssh_connected)
        com.aharou.feature.agent.domain.container.ConnectionState.CONNECTING ->
            MaterialTheme.colorScheme.tertiary to stringResource(R.string.chat_ssh_connecting)
        com.aharou.feature.agent.domain.container.ConnectionState.FAILED ->
            MaterialTheme.colorScheme.error to stringResource(R.string.chat_ssh_failed)
        com.aharou.feature.agent.domain.container.ConnectionState.DISCONNECTED ->
            MaterialTheme.colorScheme.outline to stringResource(R.string.chat_ssh_disconnected)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TokenStats(inputTokens: Int, outputTokens: Int) {
    val inStr = formatTokenCount(inputTokens.toLong())
    val outStr = formatTokenCount(outputTokens.toLong())
    Text(
        text = "↑$inStr ↓$outStr",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun RemoteConnectingPlaceholder(
    state: com.aharou.feature.agent.domain.container.ConnectionState
) {
    val text = when (state) {
        com.aharou.feature.agent.domain.container.ConnectionState.CONNECTING -> stringResource(R.string.chat_connecting_remote)
        com.aharou.feature.agent.domain.container.ConnectionState.FAILED -> stringResource(R.string.chat_remote_connect_failed)
        com.aharou.feature.agent.domain.container.ConnectionState.DISCONNECTED -> stringResource(R.string.chat_no_remote_connection)
        com.aharou.feature.agent.domain.container.ConnectionState.CONNECTED -> ""
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            if (state == com.aharou.feature.agent.domain.container.ConnectionState.CONNECTING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun WelcomeState(bottomReserve: Dp, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier.padding(Spacing.xl),
        contentAlignment = Alignment.Center
    ) {
        val targetOffset = minOf(-(maxHeight * 0.13f), -(bottomReserve / 2))
        val animatedOffset by animateDpAsState(
            targetValue = targetOffset,
            animationSpec = if (Build.VERSION.SDK_INT >= 30) snap() else tween(durationMillis = 220),
            label = "welcome-offset"
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.offset(y = animatedOffset)
        ) {
            Text(
                text = stringResource(R.string.chat_placeholder),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.chat_input_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}