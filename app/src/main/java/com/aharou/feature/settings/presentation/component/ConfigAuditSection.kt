package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.config.CONFIG_MASK_TEXT
import com.aharou.core.config.audit.ConfigAuditActor
import com.aharou.core.config.audit.ConfigAuditEntry
import com.aharou.core.config.audit.ConfigAuditLog
import com.aharou.core.config.audit.ConfigAuditStatus
import com.aharou.core.config.audit.ConfigRevert
import com.aharou.core.config.isSensitivePath
import com.aharou.core.ui.SwipeToDeleteRow
import compose.icons.FeatherIcons
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff
import compose.icons.feathericons.Trash2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * 配置审计页——自 原版 `ConfigAuditScreen` 移植适配。
 *
 * 列出配置通道的每一次写入尝试（含被拒绝/超时）：状态徽章、发起人、相对时间、
 * 字段路径、旧值 → 新值；`applied` 的记录可一键撤销（走 [ConfigRevert]，成功
 * 与否都有结果弹窗）。列表跟随 [ConfigAuditLog.revision] 自动刷新。
 */
@Composable
internal fun ConfigAuditSection() {
    val log = remember { ConfigAuditLog.get() }
    val revision by log.revision.collectAsStateWithLifecycle()
    var entries by remember { mutableStateOf<List<ConfigAuditEntry>>(emptyList()) }
    var usage by remember { mutableStateOf(ConfigAuditLog.Usage(0, 1000)) }
    var revertCandidate by remember { mutableStateOf<ConfigAuditEntry?>(null) }
    var revertResult by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deleteCandidate by remember { mutableStateOf<ConfigAuditEntry?>(null) }
    var clearConfirm by remember { mutableStateOf(false) }
    var revealSensitive by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 每次审计日志变动都重载（revision 由 append / markReverted / clearAll 触发）。
    LaunchedEffect(revision) {
        withContext(Dispatchers.IO) {
            val recent = log.recent(limit = 200)
            val u = log.usage()
            entries = recent
            usage = u
        }
    }

    // 文案提前解析：撤销协程里不能调 stringResource。
    val successTitle = stringResource(R.string.config_audit_revert_success_title)
    val successBody = stringResource(R.string.config_audit_revert_success_body)
    val failedTitle = stringResource(R.string.config_audit_revert_failed_title)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.config_audit_used_count, usage.count, usage.capacity),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (entries.any { isSensitivePath(it.key) }) {
                Icon(
                    imageVector = if (revealSensitive) FeatherIcons.EyeOff else FeatherIcons.Eye,
                    contentDescription = stringResource(
                        if (revealSensitive) {
                            R.string.config_audit_hide_sensitive
                        } else {
                            R.string.config_audit_reveal_sensitive
                        }
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { revealSensitive = !revealSensitive },
                )
            }
            if (entries.isNotEmpty()) {
                Spacer(Modifier.width(16.dp))
                Icon(
                    imageVector = FeatherIcons.Trash2,
                    contentDescription = stringResource(R.string.config_audit_clear_all),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { clearConfirm = true },
                )
            }
        }

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = FeatherIcons.Clock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.config_audit_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    SwipeToDeleteRow(onDelete = { deleteCandidate = entry }) {
                        AuditRow(
                            entry = entry,
                            revealSensitive = revealSensitive,
                            onRevert = { revertCandidate = entry },
                        )
                    }
                }
            }
        }
    }

    // 撤销确认。
    revertCandidate?.let { entry ->
        AlertDialog(
            onDismissRequest = { revertCandidate = null },
            title = { Text(stringResource(R.string.config_audit_revert_confirm_title)) },
            text = {
                Column {
                    Text(
                        text = entry.key,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${displayJSON(entry.newValueJSON, entry.key, revealSensitive)} → ${displayJSON(entry.oldValueJSON, entry.key, revealSensitive)}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = entry
                    revertCandidate = null
                    scope.launch {
                        val error = withContext(Dispatchers.IO) {
                            ConfigRevert.revert(target.id, "user-revert", null)
                        }
                        revertResult = if (error == null) {
                            successTitle to successBody
                        } else {
                            failedTitle to error
                        }
                    }
                }) {
                    Text(
                        text = stringResource(R.string.config_audit_revert),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { revertCandidate = null }) {
                    Text(stringResource(R.string.soul_cancel))
                }
            },
        )
    }

    // 删除单条确认。
    deleteCandidate?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.config_audit_delete_confirm_title)) },
            text = {
                Column {
                    Text(
                        text = entry.key,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.config_audit_delete_confirm_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = entry
                    deleteCandidate = null
                    scope.launch {
                        withContext(Dispatchers.IO) { log.delete(target.id) }
                    }
                }) {
                    Text(
                        text = stringResource(R.string.common_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text(stringResource(R.string.soul_cancel))
                }
            },
        )
    }

    // 清空全部确认。
    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text(stringResource(R.string.config_audit_clear_confirm_title)) },
            text = { Text(stringResource(R.string.config_audit_clear_confirm_body, usage.count)) },
            confirmButton = {
                TextButton(onClick = {
                    clearConfirm = false
                    scope.launch {
                        withContext(Dispatchers.IO) { log.clearAll() }
                    }
                }) {
                    Text(
                        text = stringResource(R.string.config_audit_clear_all),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirm = false }) {
                    Text(stringResource(R.string.soul_cancel))
                }
            },
        )
    }

    // 撤销结果。
    revertResult?.let { (title, bodyText) ->
        AlertDialog(
            onDismissRequest = { revertResult = null },
            title = { Text(title) },
            text = { Text(bodyText) },
            confirmButton = {
                TextButton(onClick = { revertResult = null }) {
                    Text(stringResource(R.string.soul_ok))
                }
            },
        )
    }
}

@Composable
private fun AuditRow(
    entry: ConfigAuditEntry,
    revealSensitive: Boolean,
    onRevert: () -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusBadge(entry.status)
            Spacer(Modifier.width(6.dp))
            Text(
                text = actorLabel(entry.actor),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = coarseRelativeTime(context, entry.at),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = entry.key,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = displayJSON(entry.oldValueJSON, entry.key, revealSensitive),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "→",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = displayJSON(entry.newValueJSON, entry.key, revealSensitive),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (entry.status == ConfigAuditStatus.APPLIED) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!entry.caption.isNullOrEmpty()) {
            Text(
                text = "📝 ${entry.caption}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (entry.status) {
            ConfigAuditStatus.APPLIED -> {
                AssistChip(
                    onClick = onRevert,
                    label = { Text(stringResource(R.string.config_audit_revert)) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
            ConfigAuditStatus.REVERTED -> {
                Text(
                    text = stringResource(R.string.config_audit_reverted_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8E5CD9),
                )
            }
            else -> Unit
        }
    }
}

@Composable
private fun actorLabel(actor: ConfigAuditActor): String = when (actor) {
    ConfigAuditActor.AGENT -> stringResource(R.string.config_audit_source_agent)
    ConfigAuditActor.USER -> stringResource(R.string.config_audit_source_user)
    ConfigAuditActor.AGENT_REVERT -> stringResource(R.string.config_audit_source_agent_revert)
    ConfigAuditActor.USER_REVERT -> stringResource(R.string.config_audit_source_user_revert)
}

@Composable
private fun StatusBadge(status: ConfigAuditStatus) {
    val color = when (status) {
        ConfigAuditStatus.APPLIED -> Color(0xFF388E3C)
        ConfigAuditStatus.REJECTED -> Color(0xFF757575)
        ConfigAuditStatus.TIMEOUT -> Color(0xFFE65100)
        ConfigAuditStatus.REVERTED -> Color(0xFF8E5CD9)
    }
    val label = when (status) {
        ConfigAuditStatus.APPLIED -> stringResource(R.string.config_audit_status_applied)
        ConfigAuditStatus.REJECTED -> stringResource(R.string.config_audit_status_rejected)
        ConfigAuditStatus.TIMEOUT -> stringResource(R.string.config_audit_status_timeout)
        ConfigAuditStatus.REVERTED -> stringResource(R.string.config_audit_status_reverted)
    }
    Box(
        modifier = Modifier
            .background(color, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
            ),
            color = Color.White,
        )
    }
}

/** 粗粒度相对时间。列表每次刷新重算，不需要定时器。 */
private fun coarseRelativeTime(context: android.content.Context, epochMs: Long): String {
    val now = System.currentTimeMillis()
    val secs = ((now - epochMs) / 1000L).coerceAtLeast(0)
    return when {
        secs < 60 -> context.getString(R.string.config_audit_time_just_now)
        secs < 3600 -> context.getString(R.string.config_audit_time_min_ago, (secs / 60).toInt())
        secs < 86_400 -> context.getString(R.string.config_audit_time_hr_ago, (secs / 3600).toInt())
        else -> {
            val sameDay = (now - epochMs) < 2 * 86_400_000L
            if (sameDay) {
                context.getString(R.string.config_audit_time_yesterday)
            } else {
                DateFormat.getDateInstance(DateFormat.SHORT).format(Date(epochMs))
            }
        }
    }
}

private fun displayJSON(json: String, path: String, revealSensitive: Boolean): String {
    if (json.isEmpty()) return "—"
    // 敏感字段默认打码；存储层仍是真值，撤销要读它回写。
    if (!revealSensitive && isSensitivePath(path)) return CONFIG_MASK_TEXT
    if (json.length >= 2 && json.startsWith('"') && json.endsWith('"')) {
        return json.substring(1, json.length - 1)
    }
    return json
}
