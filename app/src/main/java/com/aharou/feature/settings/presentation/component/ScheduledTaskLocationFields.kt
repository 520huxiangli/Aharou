package com.aharou.feature.settings.presentation.component

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.input.KeyboardType
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField

/**
 * 位置围栏触发的输入区。
 *
 * 从 ScheduledTasksSection 拆出来：那个文件已到行数门禁，塞不下五个字段加两行开关。
 * 状态仍然由调用方持有（表单统一在保存时一次性提交），这里只负责展示与回调。
 */
@Composable
internal fun ScheduledTaskLocationFields(
    latitudeText: String,
    onLatitudeChange: (String) -> Unit,
    longitudeText: String,
    onLongitudeChange: (String) -> Unit,
    radiusText: String,
    onRadiusChange: (String) -> Unit,
    enterEnabled: Boolean,
    onEnterChange: (Boolean) -> Unit,
    exitEnabled: Boolean,
    onExitChange: (Boolean) -> Unit,
    coordinateInvalid: Boolean,
) {
    // 权限提示直接问系统，不靠调用方传：少一个参数，也避免两处判断口径不一致。
    val context = LocalContext.current
    val permissionMissing = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) != PackageManager.PERMISSION_GRANTED
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        AppTextField(
            value = latitudeText,
            onValueChange = onLatitudeChange,
            label = stringResource(R.string.scheduled_tasks_location_latitude),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = coordinateInvalid,
            supportingText = {
                Text(stringResource(R.string.scheduled_tasks_location_coordinate_hint))
            },
        )
        AppTextField(
            value = longitudeText,
            onValueChange = onLongitudeChange,
            label = stringResource(R.string.scheduled_tasks_location_longitude),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            isError = coordinateInvalid,
        )
        AppTextField(
            value = radiusText,
            onValueChange = { input -> onRadiusChange(input.filter { it.isDigit() }) },
            label = stringResource(R.string.scheduled_tasks_location_radius),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            supportingText = {
                Text(stringResource(R.string.scheduled_tasks_location_radius_hint))
            },
        )
        LocationSwitchRow(
            title = stringResource(R.string.scheduled_tasks_location_on_enter),
            checked = enterEnabled,
            onCheckedChange = onEnterChange,
        )
        LocationSwitchRow(
            title = stringResource(R.string.scheduled_tasks_location_on_exit),
            checked = exitEnabled,
            onCheckedChange = onExitChange,
        )
        if (coordinateInvalid) {
            Text(
                text = stringResource(R.string.scheduled_tasks_location_invalid),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (permissionMissing) {
            Text(
                text = stringResource(R.string.scheduled_tasks_location_no_permission),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun LocationSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        AppSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
