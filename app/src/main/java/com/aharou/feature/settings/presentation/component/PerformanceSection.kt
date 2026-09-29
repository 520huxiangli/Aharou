package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.util.PerformanceMonitor
import com.aharou.core.util.PerformanceSnapshot
import com.aharou.core.util.formatMemoryKb
import com.aharou.core.util.formatRatePerSec
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.Locale

/**
 * 性能分析页：CPU / 内存 / 网络当前值，随时间变化的只有 CPU 曲线。
 *
 * 采样随页面进出启停（见 [DisposableEffect]）——用户不看的时候没必要读 /proc。
 */
@Composable
internal fun PerformanceSection() {
    val context = LocalContext.current.applicationContext
    val monitor = remember(context) {
        EntryPointAccessors.fromApplication(context, PerformanceMonitorEntryPoint::class.java)
            .performanceMonitor()
    }
    val snapshot by monitor.snapshot.collectAsStateWithLifecycle()
    val cpuHistory by monitor.cpuHistory.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    DisposableEffect(monitor) {
        monitor.start(scope)
        onDispose { monitor.stop() }
    }

    val current = snapshot
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl)
    ) {
        if (current == null) {
            // 首次采样在 1s 内完成，这行只是过渡，不会长期停留。
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.settings_performance),
                    subtitle = stringResource(R.string.performance_collecting)
                )
            }
            return@Column
        }

        CpuGroup(current, cpuHistory)
        MemoryGroup(current)
        NetworkGroup(current)
    }
}

@Composable
private fun CpuGroup(snapshot: PerformanceSnapshot, history: List<Double>) {
    SettingsGroup {
        SettingsRow(
            title = stringResource(R.string.performance_app_cpu),
            subtitle = stringResource(R.string.performance_cores, Runtime.getRuntime().availableProcessors()),
            trailing = { BigValue(formatPercent(snapshot.appCpuPercent)) }
        )
        if (history.size >= 2) {
            SettingsDivider()
            Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                CpuSparkline(history)
            }
        }
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.performance_device_cpu),
            trailing = { BigValue(formatPercent(snapshot.deviceCpuPercent)) }
        )
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.performance_threads),
            trailing = { BigValue(snapshot.appThreadCount.toString()) }
        )
    }
}

@Composable
private fun MemoryGroup(snapshot: PerformanceSnapshot) {
    SettingsGroup {
        SettingsRow(
            title = stringResource(R.string.performance_app_memory),
            trailing = { BigValue(formatMemoryKb(snapshot.appPssKb)) }
        )
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.performance_device_memory),
            subtitle = stringResource(
                R.string.performance_device_memory_detail,
                snapshot.deviceTotalMb,
                snapshot.deviceAvailMb
            )
        )
    }
}

@Composable
private fun NetworkGroup(snapshot: PerformanceSnapshot) {
    SettingsGroup {
        SettingsRow(
            title = stringResource(R.string.performance_network_app),
            subtitle = stringResource(R.string.performance_network_app_note)
        )
        SettingsDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            RateCell(
                label = stringResource(R.string.performance_rx),
                value = formatRatePerSec(snapshot.appRxBytesPerSec),
                modifier = Modifier.weight(1f)
            )
            RateCell(
                label = stringResource(R.string.performance_tx),
                value = formatRatePerSec(snapshot.appTxBytesPerSec),
                modifier = Modifier.weight(1f)
            )
        }
        SettingsDivider()
        SettingsRow(
            title = stringResource(R.string.performance_network_device),
            subtitle = stringResource(R.string.performance_network_device_note)
        )
        SettingsDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            RateCell(
                label = stringResource(R.string.performance_rx),
                value = formatRatePerSec(snapshot.deviceRxBytesPerSec),
                modifier = Modifier.weight(1f)
            )
            RateCell(
                label = stringResource(R.string.performance_tx),
                value = formatRatePerSec(snapshot.deviceTxBytesPerSec),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun RateCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun BigValue(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface
    )
}

/**
 * CPU 历史曲线。
 *
 * 量程自适应：本应用 CPU 通常只有个位数百分比，固定 100% 量程会把曲线压在底线上，
 * 波动完全看不出来。下限留 10%，免得空载时一点抖动被放得很大。
 */
@Composable
private fun CpuSparkline(history: List<Double>) {
    val lineColor = MaterialTheme.semanticColors.info
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val ceiling = maxOf(10.0, (history.maxOrNull() ?: 0.0) * 1.2)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
    ) {
        drawLine(
            color = axisColor,
            start = androidx.compose.ui.geometry.Offset(0f, size.height),
            end = androidx.compose.ui.geometry.Offset(size.width, size.height),
            strokeWidth = 1f
        )
        if (history.size < 2) return@Canvas
        val stepX = size.width / (history.size - 1)
        val path = Path()
        history.forEachIndexed { index, value ->
            val x = index * stepX
            val y = size.height * (1f - (value / ceiling).toFloat())
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = lineColor, style = Stroke(width = 2.dp.toPx()))
    }
}

/** null 表示数据源不可用（如 Android 10+ 读不到 /proc/stat），显示「—」。 */
private fun formatPercent(value: Double?): String =
    if (value == null) "—" else String.format(Locale.US, "%.1f%%", value)

/** Composable 取不到注入单例（没有 ViewModel 入口），只能走 EntryPoint。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface PerformanceMonitorEntryPoint {
    fun performanceMonitor(): PerformanceMonitor
}
