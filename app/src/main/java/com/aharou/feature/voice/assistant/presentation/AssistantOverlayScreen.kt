package com.aharou.feature.voice.assistant.presentation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AppTextField
import com.aharou.feature.voice.assistant.ScreenContext
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.X

/**
 * 助手浮层：底部卡片、上方留出空白当遮罩。
 *
 * 这里刻意不做多轮历史面板、不做设置入口——它的定位是「就着当前屏幕问一句」，
 * 要长聊点卡片右上角的「在聊天里打开」，那边有完整界面。
 */
@Composable
internal fun AssistantOverlayScreen(
    screen: ScreenContext,
    state: AssistantOverlayViewModel.UiState,
    fromTile: Boolean = false,
    onSend: (String) -> Unit,
    onAction: (AssistantOverlayViewModel.QuickAction) -> Unit,
    onOpenChat: () -> Unit,
    onClose: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.turns.size, state.busy) {
        if (state.turns.isNotEmpty()) {
            listState.animateScrollToItem(state.turns.lastIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClose() },
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .imePadding()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { /* 吃掉点击，别穿透到遮罩 */ },
            shape = RoundedCornerShape(topStart = Radius.lg, topEnd = Radius.lg),
            color = MaterialTheme.semanticColors.cardSurface,
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.md)
                    .padding(top = Spacing.sm, bottom = Spacing.md)
            ) {
                Header(
                    screen = screen,
                    onOpenChat = onOpenChat,
                    onClose = onClose,
                )

                val visibleTurns = state.turns.takeLast(MAX_VISIBLE_TURNS)
                if (visibleTurns.isEmpty()) {
                    EmptyHint(screen, fromTile)
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .padding(top = Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        itemsIndexed(visibleTurns) { _, turn ->
                            TurnBubble(turn)
                        }
                    }
                }

                StatusLine(state)

                ActionRow(enabled = !state.busy, onAction = onAction)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.sm),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    AppTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = stringResource(R.string.assistant_overlay_placeholder),
                        singleLine = false,
                        maxLines = 4,
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    SendButton(
                        enabled = input.isNotBlank() && !state.busy,
                        busy = state.busy,
                        onClick = {
                            val text = input
                            input = ""
                            onSend(text)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    enabled: Boolean,
    onAction: (AssistantOverlayViewModel.QuickAction) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.sm)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        AssistantOverlayViewModel.QuickAction.entries.forEach { action ->
            Surface(
                shape = RoundedCornerShape(Radius.lg),
                color = if (enabled) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.clickable(enabled = enabled) { onAction(action) },
            ) {
                Text(
                    text = stringResource(action.labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                )
            }
        }
    }
}

@Composable
private fun Header(screen: ScreenContext, onOpenChat: () -> Unit, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.assistant_overlay_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            val label = screen.appLabel ?: screen.packageName
            Text(
                text = if (label.isNullOrBlank()) {
                    stringResource(R.string.assistant_overlay_source_unknown)
                } else {
                    stringResource(R.string.assistant_overlay_source, label)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onOpenChat) {
            Text(stringResource(R.string.assistant_overlay_open_chat))
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = FeatherIcons.X,
                contentDescription = stringResource(R.string.assistant_overlay_close),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun EmptyHint(screen: ScreenContext, fromTile: Boolean) {
    Text(
        text = when {
            screen.updatedAtMs == 0L && fromTile -> stringResource(R.string.assistant_overlay_empty_hint)
            screen.updatedAtMs == 0L -> stringResource(R.string.assistant_overlay_screen_loading)
            screen.hasText -> stringResource(R.string.assistant_overlay_empty_hint)
            else -> stringResource(R.string.assistant_overlay_screen_unavailable)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs),
    )
}

@Composable
private fun StatusLine(state: AssistantOverlayViewModel.UiState) {
    val status = state.error ?: state.status
    val busy = state.busy && state.error == null
    if (status == null && !busy) return
    Text(
        text = when {
            state.error != null -> stringResource(R.string.assistant_overlay_failed, status.orEmpty())
            status != null -> stringResource(R.string.assistant_overlay_tool, status)
            else -> stringResource(R.string.assistant_overlay_thinking)
        },
        style = MaterialTheme.typography.labelSmall,
        color = if (state.error != null) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.xs),
    )
}

@Composable
private fun SendButton(enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(Radius.mdLarge),
        color = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .size(44.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Icon(
                    imageVector = FeatherIcons.ArrowUp,
                    contentDescription = stringResource(R.string.assistant_overlay_send),
                    tint = if (enabled) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun TurnBubble(turn: AssistantOverlayViewModel.Turn) {
    val isUser = turn.role == AssistantOverlayViewModel.Role.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 520.dp),
            shape = RoundedCornerShape(Radius.mdLarge),
            color = if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            else MaterialTheme.semanticColors.cardSurface,
        ) {
            Text(
                text = turn.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            )
        }
    }
}

private const val MAX_VISIBLE_TURNS = 6
