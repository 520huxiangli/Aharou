package com.aharou.core.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aharou.core.theme.Radius
import com.aharou.core.theme.semanticColors

/**
 * 全局统一的 App 输入框颜色规范：
 * - 浅色模式：主题卡片色背景 + 柔和外边框 + 聚焦主色高光；
 * - 暗色模式：深色表面底色（surface/surfaceVariant）+ 描边 + 聚焦主色微光。
 */
@Composable
fun appTextFieldColors(
    isLight: Boolean = MaterialTheme.colorScheme.background.luminance() > 0.5f,
    unfocusedContainerColor: Color = MaterialTheme.semanticColors.cardSurface,
    focusedContainerColor: Color = if (isLight) MaterialTheme.semanticColors.cardSurface
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
): TextFieldColors {
    return OutlinedTextFieldDefaults.colors(
        unfocusedBorderColor = if (isLight) MaterialTheme.colorScheme.outlineVariant
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedContainerColor = unfocusedContainerColor,
        focusedContainerColor = focusedContainerColor,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        cursorColor = MaterialTheme.colorScheme.primary,
        focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
        unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        focusedTrailingIconColor = MaterialTheme.colorScheme.primary,
        unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        // 禁用态沿用未聚焦时的底色：M3 默认把 disabled 容器色置为透明，enabled 一变背景就在有色与透明之间跳一下，
        // 看着就是输入框在闪。禁用语义交给较浅的边框与文字表达。
        disabledContainerColor = unfocusedContainerColor,
        disabledBorderColor = if (isLight) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    )
}

/**
 * 弹窗（AlertDialog 等自带主题底色的容器）里的输入框配色：容器全透明，底色直接透出弹窗自身的颜色。
 *
 * 弹窗底色随主题变，而普通页面用的卡片白底放到这种弹窗里会像贴上去的一块白纸。
 */
@Composable
fun dialogTextFieldColors(): TextFieldColors = appTextFieldColors(
    unfocusedContainerColor = Color.Transparent,
    focusedContainerColor = Color.Transparent
)

/**
 * 全局统一的基础输入框组件：
 * 采用 12dp 圆角卡片质感，自动适配浅色纯白/深色深蓝背景，支持前缀/后缀图标与完整键盘配置。
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    label: String? = null,
    placeholder: String? = null,
    labelComposable: (@Composable () -> Unit)? = null,
    placeholderComposable: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    shape: Shape = RoundedCornerShape(Radius.mdLarge),
    colors: TextFieldColors = appTextFieldColors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    val actualLabel: (@Composable () -> Unit)? = labelComposable ?: label?.let { { Text(it) } }
    val actualPlaceholder: (@Composable () -> Unit)? = placeholderComposable ?: placeholder?.let { { Text(it) } }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = actualLabel,
        placeholder = actualPlaceholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = shape,
        colors = colors
    )
}
