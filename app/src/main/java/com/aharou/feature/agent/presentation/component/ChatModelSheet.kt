package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.aharou.core.ui.AdaptiveModalBottomSheet
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.aharou.feature.onboarding.domain.OnboardingStep
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.data.local.CustomModelMetadataStore
import com.aharou.feature.settings.domain.model.ModelMetadata
import com.aharou.feature.settings.domain.model.modelMetadataKey
import com.aharou.feature.settings.presentation.component.ModelLogoIcon
import com.aharou.feature.settings.presentation.component.ModelSelectionSheet
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Zap

/**
 * 输入区下行的模型切换图标按钮
 */
@Composable
internal fun ModelIconButton(
    provider: AIProviderConfig?,
    providers: List<AIProviderConfig>,
    modelMetadata: Map<String, ModelMetadata>,
    onSelectModel: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    forceOpenSheet: Boolean = false,
    onSelectSuccessInOnboarding: (() -> Unit)? = null,
    onSheetDismiss: (() -> Unit)? = null
) {
    var showSheet by remember { mutableStateOf(false) }
    val effectiveShowSheet = showSheet || forceOpenSheet

    // 自定义图标存在本地元数据里，自动解析的 modelMetadata 里没有它；这里单独取一次，
    // 让输入区的小图标跟模型列表保持一致（2026-10-26 issue #33 追加反馈）。
    val context = LocalContext.current
    val customIconStore = remember { CustomModelMetadataStore(context.applicationContext) }
    var customIcon by remember { mutableStateOf("") }
    val effectiveModel = provider?.effectiveModel.orEmpty()
    LaunchedEffect(provider?.id, effectiveModel) {
        customIcon = customIconStore.all()[modelMetadataKey(provider?.id.orEmpty(), effectiveModel)]
            ?.customIcon?.takeIf { it.isNotBlank() }
            ?: modelMetadata[modelMetadataKey(provider?.id.orEmpty(), effectiveModel)]?.customIcon.orEmpty()
    }
    IconButton(onClick = { showSheet = true }, modifier = modifier.size(36.dp)) {
        ModelLogoIcon(modelName = effectiveModel, customIcon = customIcon, size = 20.dp)
    }

    if (effectiveShowSheet) {
        ModelSheet(
            providers = providers,
            currentProviderId = provider?.id ?: "",
            currentModel = provider?.effectiveModel ?: "",
            modelMetadata = modelMetadata,
            onSelect = { pId, model ->
                onSelectModel(pId, model)
                showSheet = false
                onSelectSuccessInOnboarding?.invoke()
            },
            onDismiss = {
                showSheet = false
                onSheetDismiss?.invoke()
            }
        )
    }
}

/**
 * 思考强度选择器：独立图标按钮，点击弹出底部档位选择。
 * [availableEfforts] 由调用方按当前模型元数据生成，元数据未命中时给出全部档位。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReasoningEffortSelector(
    effort: ReasoningEffort,
    availableEfforts: List<ReasoningEffort>,
    onChange: (ReasoningEffort) -> Unit,
    enabled: Boolean,
    modelMetadata: ModelMetadata? = null
) {
    var showSheet by remember { mutableStateOf(false) }
    Box {
        // 图标旁直接带档位文字：档位不同耗时与花费差得多，只给图标看不出当前选了哪档。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(Radius.sm))
                .clickable(enabled = enabled) { showSheet = true }
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm)
        ) {
            val tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            Icon(
                FeatherIcons.Zap,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = reasoningEffortLabel(effort),
                style = MaterialTheme.typography.labelMedium,
                color = tint,
                maxLines = 1
            )
        }
    }

    if (showSheet) {
        val sheetState = rememberModalBottomSheetState()
        AdaptiveModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.xl)
            ) {
                Text(
                    text = stringResource(com.aharou.R.string.chat_reasoning_effort),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = Spacing.sm)
                )
                val declaredEfforts = modelMetadata?.reasoningEffortOptions
                // 声明了档位的模型，档位表本身就是说明；纯开关型/预算型才需一句话点明区别。
                if (declaredEfforts.isNullOrEmpty()) {
                    when {
                        modelMetadata?.reasoningBudgetTokens == true -> EffortHint(
                            stringResource(R.string.model_reasoning_budget_tokens),
                            MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        modelMetadata?.reasoningSupportsToggle == true -> EffortHint(
                            stringResource(R.string.model_reasoning_toggle_only),
                            MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // 只看元数据声明，不在这里复刻适配器的降级规则（xhigh/max 归一 high、老代际换算预算），
                // UI 再算一遍必然与实现漂移。DEFAULT 是不指定档位，服务端默认总是成立，不提醒；
                // 元数据没命中（INFERRED 兜底）时对模型一无所知，也不吓人。
                // 纯开关型模型选「不思考」正是它声明的关态，不算未声明档位。
                val toggleExpressesNone = modelMetadata?.reasoningSupportsToggle == true && effort == ReasoningEffort.NONE
                val reasoningCapable = modelMetadata?.supportsReasoning == true || !declaredEfforts.isNullOrEmpty()
                // apiValue 是 String?（DEFAULT 为 null），先取局部变量再比对，否则 contains 的类型对不上。
                val effortValue = effort.apiValue
                if (reasoningCapable && effortValue != null && !toggleExpressesNone &&
                    declaredEfforts?.contains(effortValue) != true
                ) {
                    EffortHint(
                        stringResource(R.string.model_reasoning_effort_unverified),
                        MaterialTheme.colorScheme.tertiary
                    )
                }
                availableEfforts.forEach { e ->
                    val selected = e == effort
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.sm))
                            .clickable {
                                showSheet = false
                                onChange(e)
                            }
                            .padding(horizontal = Spacing.md, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            FeatherIcons.Zap,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(Spacing.md))
                        Text(
                            text = reasoningEffortLabel(e),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (selected) {
                            Icon(
                                FeatherIcons.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 档位弹层里的一行说明小字（能力形态 / 档位未在元数据里声明）。 */
@Composable
private fun EffortHint(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = Modifier.padding(bottom = Spacing.sm)
    )
}

/**
 * 档位显示文案：中文名后面跟上接口真实取值（如「最高（max）」）。
 * 用户对照模型文档或看 400 报错时看到的是 none / low / medium / max 这些值，只给中文名对不上。
 */
@Composable
internal fun reasoningEffortLabel(effort: ReasoningEffort): String {
    val name = stringResource(effort.labelRes())
    // DEFAULT 表示请求里不带该字段，没有取值为空串可显示，「默认（）」只会让人以为漏了参数。
    val apiValue = effort.apiValue ?: return name
    return stringResource(R.string.common_label_with_api, name, apiValue)
}

internal fun ReasoningEffort.labelRes(): Int = when (this) {
    ReasoningEffort.DEFAULT -> com.aharou.R.string.chat_reasoning_effort_default
    ReasoningEffort.NONE -> com.aharou.R.string.chat_reasoning_effort_none
    ReasoningEffort.LOW -> com.aharou.R.string.chat_reasoning_effort_low
    ReasoningEffort.MEDIUM -> com.aharou.R.string.chat_reasoning_effort_medium
    ReasoningEffort.HIGH -> com.aharou.R.string.chat_reasoning_effort_high
    ReasoningEffort.XHIGH -> com.aharou.R.string.chat_reasoning_effort_xhigh
    ReasoningEffort.MAX -> com.aharou.R.string.chat_reasoning_effort_max
}

/**
 * 模型选择弹窗：复用设置页默认模型的 [ModelSelectionSheet]（iOS 胶囊搜索框、提供商分组卡片、能力 Tag）。
 * 行内「测试」入口也随 [ModelSelectionSheet] 一起来——弹层自己持测试状态，聊天页无需额外参数或接线。
 */
@Composable
internal fun ModelSheet(
    providers: List<AIProviderConfig>,
    currentProviderId: String,
    currentModel: String,
    modelMetadata: Map<String, ModelMetadata>,
    onSelect: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    ModelSelectionSheet(
        title = stringResource(R.string.common_model),
        noModelsText = stringResource(R.string.chat_no_models_hint),
        providers = providers,
        currentProviderId = currentProviderId,
        currentModel = currentModel,
        modelMetadata = modelMetadata,
        onSelect = onSelect,
        onClear = null,
        onDismiss = onDismiss
    )
}
