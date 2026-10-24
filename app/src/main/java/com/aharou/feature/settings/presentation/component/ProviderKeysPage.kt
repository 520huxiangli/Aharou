package com.aharou.feature.settings.presentation.component

import com.aharou.feature.onboarding.domain.OnboardingStep
import com.aharou.feature.onboarding.presentation.onboardingTarget
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.SwipeToDeleteRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.aharou.core.ui.AppSwitch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import com.aharou.core.util.PanelScriptImporter
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.background
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.FloatingTabBar
import com.aharou.core.ui.FloatingTabItem
import com.aharou.feature.settings.data.local.CustomModelMetadataStore
import com.aharou.feature.settings.data.local.ProviderPreset
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.KeyRotationStrategy
import com.aharou.feature.settings.domain.model.ModelMetadata
import com.aharou.feature.settings.domain.model.MAX_KEY_WEIGHT
import com.aharou.feature.settings.domain.model.ProviderKey
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.data.repository.ProxyConfig
import com.aharou.feature.settings.domain.model.ProxyType
import com.aharou.feature.settings.domain.model.mergeModelMetadata
import com.aharou.feature.settings.domain.model.modelMetadataKey
import com.aharou.feature.settings.domain.model.sanitized
import com.aharou.feature.settings.presentation.FetchState
import com.aharou.feature.settings.presentation.SettingsViewModel
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aharou.core.ui.ExpandableChevronIcon
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Check
import compose.icons.feathericons.CheckSquare
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.ChevronUp
import android.content.Intent
import android.widget.Toast
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.DownloadCloud
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Minus
import compose.icons.feathericons.Play
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Upload
import compose.icons.feathericons.X
import com.aharou.feature.agent.presentation.component.AdaptiveCardView
import com.aharou.feature.settings.domain.model.ProviderDashboardResult
import com.aharou.feature.settings.domain.model.ProviderDashboardState
import androidx.compose.ui.res.stringResource
import com.aharou.R
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderKeysPage(
    keys: MutableList<ProviderKey>,
    strategy: KeyRotationStrategy,
    cooldownMinutes: Int,
    switchStatusCodes: String,
    onBack: () -> Unit,
    onSetStrategy: (KeyRotationStrategy) -> Unit,
    onSetCooldownMinutes: (Int) -> Unit,
    onSetSwitchStatusCodes: (String) -> Unit
) {
    // 行稳定 id：Key 值可被就地编辑，不能拿它当 LazyColumn 的 key，否则每敲一个字都换一次身份、输入框会失焦。
    val rowIds = remember { mutableStateListOf<Int>().apply { keys.indices.forEach { add(it) } } }
    var nextRowId by remember { mutableIntStateOf(keys.size) }
    var expandedIds by remember { mutableStateOf(emptySet<Int>()) }
    val hapticFeedback = LocalHapticFeedback.current

    fun addKey() {
        val rowId = nextRowId
        keys.add(ProviderKey(""))
        rowIds.add(rowId)
        expandedIds = expandedIds + rowId
        nextRowId = rowId + 1
    }

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // onMove 下标按整个 LazyColumn 算，减去前置小标题项才是 Key 下标；落在策略/失败切换区则忽略。
        val fromIndex = from.index - PROVIDER_KEYS_LEADING_ITEMS
        val toIndex = to.index - PROVIDER_KEYS_LEADING_ITEMS
        if (fromIndex !in keys.indices || toIndex !in keys.indices || fromIndex == toIndex) {
            return@rememberReorderableLazyListState
        }
        keys.add(toIndex, keys.removeAt(fromIndex))
        rowIds.add(toIndex, rowIds.removeAt(fromIndex))
    }

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.provider_multi_key_manage)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { addKey() }) {
                        Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.provider_multi_key_add))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            item {
                SettingsGroupHeader(text = stringResource(R.string.provider_multi_key_list))
            }

            if (keys.isEmpty()) {
                item {
                    SettingsGroup {
                        SettingsRow(
                            icon = null,
                            title = stringResource(R.string.provider_multi_key_empty),
                            onClick = { addKey() }
                        )
                    }
                }
            } else {
                itemsIndexed(items = keys, key = { index, _ -> rowIds[index] }) { index, key ->
                    val rowId = rowIds[index]
                    ReorderableItem(state = reorderableState, key = rowId) { isDragging ->
                        val dragScale by animateFloatAsState(
                            targetValue = if (isDragging) 0.95f else 1f,
                            animationSpec = tween(durationMillis = if (isDragging) 120 else 220),
                            label = "providerKeyDragScale"
                        )
                        val dragElevation by animateDpAsState(
                            targetValue = if (isDragging) 8.dp else 0.dp,
                            animationSpec = tween(durationMillis = if (isDragging) 120 else 220),
                            label = "providerKeyDragElevation"
                        )
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.semanticColors.cardSurface,
                            shadowElevation = dragElevation,
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isDragging) 1f else 0f)
                                .graphicsLayer {
                                    scaleX = dragScale
                                    scaleY = dragScale
                                }
                        ) {
                            SwipeToDeleteRow(
                                onDelete = {
                                    keys.removeAt(index)
                                    rowIds.removeAt(index)
                                },
                                onClick = {
                                    expandedIds = if (rowId in expandedIds) expandedIds - rowId else expandedIds + rowId
                                }
                            ) {
                                ProviderKeyCard(
                                    index = index,
                                    key = key,
                                    expanded = rowId in expandedIds,
                                    onValueChange = { keys[index] = keys[index].copy(value = it) },
                                    onWeightChange = { keys[index] = keys[index].copy(weight = it) },
                                    dragModifier = Modifier.longPressDraggableHandle(
                                        onDragStarted = {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                        },
                                        onDragStopped = {
                                            hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                        }
                                    )
                                )
                            }
                        }
                    }
                }
            }

            item { SettingsGroupHeader(text = stringResource(R.string.provider_multi_key_strategy)) }
            item {
                SettingsGroup {
                    KeyRotationStrategy.entries.forEachIndexed { index, item ->
                        if (index > 0) SettingsDivider()
                        SettingsRow(
                            icon = null,
                            title = keyRotationStrategyLabel(item),
                            subtitle = stringResource(
                                when (item) {
                                    KeyRotationStrategy.SEQUENTIAL -> R.string.provider_multi_key_strategy_sequential_desc
                                    KeyRotationStrategy.ROUND_ROBIN -> R.string.provider_multi_key_strategy_round_robin_desc
                                }
                            ),
                            onClick = { onSetStrategy(item) },
                            trailing = {
                                if (item == strategy) {
                                    Icon(
                                        imageVector = FeatherIcons.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        )
                    }
                }
            }

            item { SettingsGroupHeader(text = stringResource(R.string.provider_multi_key_failover)) }
            item {
                SettingsGroup {
                    ProviderTextFieldRow(
                        label = stringResource(R.string.provider_multi_key_status_codes),
                        value = switchStatusCodes,
                        onValueChange = { input -> onSetSwitchStatusCodes(input.filter { it.isDigit() || it == ',' }) },
                        placeholder = stringResource(R.string.provider_multi_key_status_codes_hint)
                    )
                    SettingsDivider()
                    StepperRow(
                        title = stringResource(R.string.provider_multi_key_cooldown),
                        subtitle = stringResource(R.string.provider_multi_key_cooldown_desc),
                        valueText = if (cooldownMinutes <= 0) {
                            stringResource(R.string.provider_multi_key_cooldown_off)
                        } else {
                            stringResource(R.string.provider_multi_key_cooldown_value, cooldownMinutes)
                        },
                        onDecrease = { onSetCooldownMinutes((cooldownMinutes - 1).coerceAtLeast(0)) },
                        onIncrease = { onSetCooldownMinutes((cooldownMinutes + 1).coerceAtMost(120)) }
                    )
                }
            }
        }
    }
}

/** Key 列表前的固定项数量：一个分组小标题。拖拽回调下标需减去它才能映射到 Key 下标。 */
private const val PROVIDER_KEYS_LEADING_ITEMS = 1

/**
 * 单条 Key 卡片：折叠时只显示序号与掩码预览，展开后可编辑 Key 与调度权重。
 * 长按头部拖动可调整顺序（[dragModifier] 由 ReorderableItem 作用域提供）。
 */
@Composable
private fun ProviderKeyCard(
    index: Int,
    key: ProviderKey,
    expanded: Boolean,
    onValueChange: (String) -> Unit,
    onWeightChange: (Int) -> Unit,
    dragModifier: Modifier
) {
    var valueVisible by remember { mutableStateOf(false) }
    val sortDescription = stringResource(R.string.provider_sort_long_press)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(dragModifier)
                .padding(start = Spacing.lg, end = Spacing.md, top = 11.dp, bottom = 11.dp)
                .semantics { contentDescription = sortDescription },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.provider_multi_key_item, index + 1),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = key.value.maskedKeyPreview(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            ExpandableChevronIcon(
                expanded = expanded,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                size = 18.dp
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SettingsDivider()
                ProviderTextFieldRow(
                    label = stringResource(R.string.provider_api_key),
                    value = key.value,
                    onValueChange = onValueChange,
                    visualTransformation = if (valueVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailing = {
                        Icon(
                            imageVector = if (valueVisible) FeatherIcons.EyeOff else FeatherIcons.Eye,
                            contentDescription = stringResource(
                                if (valueVisible) R.string.provider_hide_api_key else R.string.provider_show_api_key
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { valueVisible = !valueVisible }
                        )
                    }
                )
                ProviderTextFieldRow(
                    label = stringResource(R.string.provider_multi_key_weight),
                    value = key.weight.toString(),
                    onValueChange = { input ->
                        onWeightChange(
                            input.filter { it.isDigit() }.take(7).toIntOrNull()?.coerceAtMost(MAX_KEY_WEIGHT) ?: 0
                        )
                    },
                    keyboardType = KeyboardType.Number
                )
                Text(
                    text = stringResource(R.string.provider_multi_key_weight_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.md)
                )
            }
        }
    }
}

/** 卡片头部的密钥掩码预览：只露前两位，避免整串明文出现在列表里。 */
private fun String.maskedKeyPreview(): String = when {
    isBlank() -> ""
    length <= 8 -> "******"
    else -> "${take(2)}*****..."
}

/** 分组内数字步进行：标题 + 副标题，右侧「− 值 +」。 */
@Composable
private fun StepperRow(
    title: String,
    subtitle: String,
    valueText: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.sm, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onDecrease, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = FeatherIcons.Minus,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text = valueText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.xs)
        )
        IconButton(onClick = onIncrease, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = FeatherIcons.Plus,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
