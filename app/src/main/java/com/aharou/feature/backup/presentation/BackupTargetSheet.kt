package com.aharou.feature.backup.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.SegmentedTabs
import com.aharou.feature.backup.domain.remote.BackupTarget
import com.aharou.feature.backup.domain.remote.BackupTargetType
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff
import java.util.UUID

/**
 * 新建 / 编辑网络备份目标（WebDAV / S3）的底部弹层表单。
 * 顶部用 [SegmentedTabs] 切换协议，字段随类型增减；保存时回传 [BackupTarget]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackupTargetSheet(
    initial: BackupTarget?,
    onDismiss: () -> Unit,
    onSave: (BackupTarget) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var type by remember(initial) { mutableStateOf(initial?.type ?: BackupTargetType.WEBDAV) }
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var endpoint by remember(initial) { mutableStateOf(initial?.endpoint ?: "") }
    var basePath by remember(initial) { mutableStateOf(initial?.basePath ?: "") }
    var username by remember(initial) { mutableStateOf(initial?.username ?: "") }
    var password by remember(initial) { mutableStateOf(initial?.password ?: "") }
    var accessKey by remember(initial) { mutableStateOf(initial?.accessKey ?: "") }
    var secretKey by remember(initial) { mutableStateOf(initial?.secretKey ?: "") }
    var sessionToken by remember(initial) { mutableStateOf(initial?.sessionToken ?: "") }
    var bucket by remember(initial) { mutableStateOf(initial?.bucket ?: "") }
    var region by remember(initial) { mutableStateOf(initial?.region?.ifBlank { DEFAULT_REGION } ?: DEFAULT_REGION) }
    var pathStyle by remember(initial) { mutableStateOf(initial?.pathStyle ?: false) }
    var secretVisible by remember { mutableStateOf(false) }

    val canSave = name.isNotBlank() && when (type) {
        BackupTargetType.WEBDAV -> endpoint.isNotBlank()
        BackupTargetType.S3 -> bucket.isNotBlank() && accessKey.isNotBlank() && secretKey.isNotBlank()
    }

    fun buildTarget(): BackupTarget = BackupTarget(
        id = initial?.id ?: UUID.randomUUID().toString(),
        name = name.trim(),
        type = type,
        endpoint = endpoint.trim(),
        basePath = basePath.trim(),
        username = username,
        password = password,
        accessKey = accessKey.trim(),
        secretKey = secretKey,
        sessionToken = sessionToken,
        bucket = bucket.trim(),
        region = region.trim().ifBlank { DEFAULT_REGION },
        pathStyle = pathStyle,
        createdAt = initial?.createdAt ?: 0L
    )

    AdaptiveModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
                .verticalScroll(rememberScrollState())
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = if (initial == null) {
                    stringResource(R.string.backup_net_new_target)
                } else {
                    stringResource(R.string.backup_net_edit_target)
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = Spacing.xs)
            )

            SegmentedTabs(
                selected = type.ordinal,
                labels = listOf(WEBDAV_LABEL, S3_LABEL),
                onSelect = { type = BackupTargetType.entries[it] }
            )

            TargetField(name, { name = it }, stringResource(R.string.backup_net_name))

            if (type == BackupTargetType.WEBDAV) {
                TargetField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = stringResource(R.string.backup_net_endpoint),
                    placeholder = stringResource(R.string.backup_net_endpoint_hint)
                )
                TargetField(basePath, { basePath = it }, stringResource(R.string.backup_net_base_path))
                TargetField(username, { username = it }, stringResource(R.string.backup_net_username))
                TargetField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.backup_net_password),
                    secret = true,
                    secretVisible = secretVisible,
                    onToggleSecret = { secretVisible = !secretVisible }
                )
            } else {
                TargetField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = stringResource(R.string.backup_net_endpoint),
                    placeholder = stringResource(R.string.backup_net_endpoint_optional_hint)
                )
                TargetField(bucket, { bucket = it }, stringResource(R.string.backup_net_bucket))
                TargetField(
                    value = region,
                    onValueChange = { region = it },
                    label = stringResource(R.string.backup_net_region),
                    placeholder = DEFAULT_REGION
                )
                TargetField(accessKey, { accessKey = it }, stringResource(R.string.backup_net_access_key))
                TargetField(
                    value = secretKey,
                    onValueChange = { secretKey = it },
                    label = stringResource(R.string.backup_net_secret_key),
                    secret = true,
                    secretVisible = secretVisible,
                    onToggleSecret = { secretVisible = !secretVisible }
                )
                TargetField(
                    value = sessionToken,
                    onValueChange = { sessionToken = it },
                    label = stringResource(R.string.backup_net_session_token)
                )
                TargetField(basePath, { basePath = it }, stringResource(R.string.backup_net_object_prefix))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.backup_net_path_style),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.backup_net_path_style_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    AppSwitch(checked = pathStyle, onCheckedChange = { pathStyle = it })
                }
            }

            if (!canSave) {
                Text(
                    text = stringResource(R.string.backup_net_required_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                Button(
                    onClick = { onSave(buildTarget()) },
                    enabled = canSave,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(FeatherIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.common_save),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

/** 统一的表单字段包装：一律走全局 [AppTextField]。 */
@Composable
private fun TargetField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String? = null,
    secret: Boolean = false,
    secretVisible: Boolean = false,
    onToggleSecret: (() -> Unit)? = null
) {
    val revealIcon: (@Composable () -> Unit)? = if (secret && onToggleSecret != null) {
        val toggle = onToggleSecret
        {
            IconButton(onClick = toggle) {
                Icon(
                    imageVector = if (secretVisible) FeatherIcons.EyeOff else FeatherIcons.Eye,
                    contentDescription = null
                )
            }
        }
    } else {
        null
    }

    AppTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        singleLine = true,
        visualTransformation = if (secret && !secretVisible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = revealIcon,
        modifier = Modifier.fillMaxWidth()
    )
}

private const val DEFAULT_REGION = "us-east-1"
private const val WEBDAV_LABEL = "WebDAV"
private const val S3_LABEL = "S3"
