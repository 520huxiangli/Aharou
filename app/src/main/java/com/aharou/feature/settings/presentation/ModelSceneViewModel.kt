package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aharou.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aharou.feature.settings.data.repository.ImageGenModelSettingsRepository
import com.aharou.feature.settings.data.repository.TitleModelSettingsRepository
import com.aharou.feature.settings.data.repository.VisionModelSettingsRepository
import com.aharou.feature.settings.domain.model.ModelScene
import com.aharou.feature.settings.domain.model.SceneResolution
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import com.aharou.feature.settings.domain.service.ModelSceneResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 「各场景实际生效的模型」只读视图。
 *
 * 只负责把 [ModelSceneResolver] 的结果摊平成 UI 状态，不持有任何可写入口：
 * 绑定本身仍由「设置 → 默认模型」各行写各角色的仓库，避免两处都能改造成状态漂移。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class ModelSceneViewModel @Inject constructor(
    private val resolver: ModelSceneResolver,
    providerRepository: AIProviderRepository,
    defaultModel: DefaultModelSettingsRepository,
    titleModel: TitleModelSettingsRepository,
    compactionModel: CompactionModelSettingsRepository,
    visionModel: VisionModelSettingsRepository,
    imageGenModel: ImageGenModelSettingsRepository,
) : ViewModel() {

    /** 任一处绑定或供应商列表变化都会触发重算。 */
    private val invalidations: Flow<Unit> = merge<Any>(
        providerRepository.getAllProviders(),
        defaultModel.providerIdFlow,
        defaultModel.modelFlow,
        titleModel.providerIdFlow,
        titleModel.modelFlow,
        compactionModel.providerIdFlow,
        compactionModel.modelFlow,
        visionModel.providerIdFlow,
        visionModel.modelFlow,
        imageGenModel.providerIdFlow,
        imageGenModel.modelFlow,
    ).map { }

    val resolutions: StateFlow<Map<ModelScene, SceneResolution>> = invalidations
        .mapLatest { resolver.resolveAll() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}
