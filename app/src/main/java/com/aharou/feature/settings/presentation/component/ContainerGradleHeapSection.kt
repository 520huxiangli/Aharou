package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import com.aharou.feature.settings.presentation.ContainerGradleHeapViewModel
import kotlin.math.roundToInt

/**
 * 容器页的「Gradle 堆上限」卡片。
 *
 * 这个值写进容器用户的 `~/.gradle/gradle.properties`，覆盖项目里写死的 `org.gradle.jvmargs`
 * （用户级优先于项目级），所以同一台机器上的任意工程都按这里的上限编译。选低了编译慢或不稳，
 * 选高了会被系统 OOM 杀掉，因此默认按设备内存给推荐值、允许用户自己调。
 *
 * 抽成独立文件是为了不让 [ContainerSettingsSection] 撞上行数棘轮。
 */
@Composable
internal fun ContainerGradleHeapSection(
    viewModel: ContainerGradleHeapViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var sliderMb by remember(state.heapMb) { mutableFloatStateOf(state.heapMb.toFloat()) }

    Spacer(Modifier.height(Spacing.md))
    SettingsGroup {
        SettingsRow(
            title = stringResource(R.string.container_gradle_heap_title),
            subtitle = stringResource(
                R.string.container_gradle_heap_desc,
                // totalMem 报的是内核物理内存（8GB 机器约 7.4~7.8GB），四舍五入到标称 GB。
                (state.deviceMemMb + 512) / 1024,
                state.recommendedMb
            ),
            trailing = {
                Text(
                    text = stringResource(
                        R.string.container_gradle_heap_value,
                        sliderMb.roundToInt()
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        )
        // 滑条自带刻度轨高度，只补左右缩进对齐行内容；上下与 SettingsRow 的 11.dp 不叠加。
        Slider(
            value = sliderMb,
            onValueChange = { sliderMb = it },
            valueRange = ContainerSettingsRepository.GRADLE_HEAP_MIN_MB.toFloat()..
                ContainerSettingsRepository.GRADLE_HEAP_MAX_MB.toFloat(),
            steps = (ContainerSettingsRepository.GRADLE_HEAP_MAX_MB -
                ContainerSettingsRepository.GRADLE_HEAP_MIN_MB) /
                ContainerSettingsRepository.GRADLE_HEAP_STEP_MB - 1,
            onValueChangeFinished = { viewModel.setHeapMb(sliderMb.roundToInt()) },
            modifier = Modifier.padding(horizontal = Spacing.lg)
        )
    }
    Spacer(Modifier.height(Spacing.md))
}
