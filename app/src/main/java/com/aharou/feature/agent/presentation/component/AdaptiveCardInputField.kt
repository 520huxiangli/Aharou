package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.aharou.core.ui.AppTextField
import com.aharou.feature.settings.domain.model.AdaptiveCardElement
import com.aharou.feature.settings.domain.model.InputNumberElement
import com.aharou.feature.settings.domain.model.InputTextElement

/** 面板输入框的当前值，键为脚本里写的元素 id：由聊天页按会话持有，渲染层只读。 */
internal val LocalDashboardInputs = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/** 输入框改动回传给宿主的回调：渲染层不自己存值，否则脚本重跑后输入会被卡片里的默认值覆盖。 */
internal val LocalDashboardInputChange = staticCompositionLocalOf<(String, String) -> Unit> { { _, _ -> } }

@Composable
internal fun DashboardInputsProvider(
    inputs: Map<String, String>,
    onInputChange: (String, String) -> Unit,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalDashboardInputs provides inputs,
        LocalDashboardInputChange provides onInputChange,
        content = content
    )
}

@Composable
internal fun RenderInputElement(element: AdaptiveCardElement, modifier: Modifier = Modifier) {
    when (element) {
        is InputTextElement -> RenderInputText(element, modifier)
        is InputNumberElement -> RenderInputNumber(element, modifier)
        else -> Unit
    }
}

@Composable
internal fun RenderInputText(element: InputTextElement, modifier: Modifier = Modifier) {
    RenderInputField(element.id, element.label, element.placeholder, element.value, KeyboardType.Text, modifier)
}

@Composable
internal fun RenderInputNumber(element: InputNumberElement, modifier: Modifier = Modifier) {
    RenderInputField(element.id, element.label, element.placeholder, element.value, KeyboardType.Number, modifier)
}

@Composable
private fun RenderInputField(
    id: String,
    label: String,
    placeholder: String?,
    defaultValue: String,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier
) {
    val inputs = LocalDashboardInputs.current
    val onInputChange = LocalDashboardInputChange.current
    AppTextField(
        value = inputs[id] ?: defaultValue,
        onValueChange = { onInputChange(id, it) },
        modifier = modifier.fillMaxWidth(),
        label = label.ifBlank { null },
        placeholder = placeholder,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
    )
}
