package com.aharou.feature.settings.domain.service

import com.aharou.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aharou.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aharou.feature.settings.data.repository.ImageGenModelSettingsRepository
import com.aharou.feature.settings.data.repository.TitleModelSettingsRepository
import com.aharou.feature.settings.data.repository.VisionModelSettingsRepository
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ModelScene
import com.aharou.feature.settings.domain.model.SceneBinding
import com.aharou.feature.settings.domain.model.SceneBindingIssue
import com.aharou.feature.settings.domain.model.SceneResolution
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 场景 → (provider, model) 的唯一解析入口。
 *
 * 只读：解析结果不落盘，绑定本身仍存在各角色原有的 DataStore 里（见 [ModelScene.configPath]）。
 *
 * 回退规则（与现有运行期行为一致，不新造一层语义）：
 * 1. 场景自己的绑定可用（供应商存在、已启用、有可用 Key）→ [SceneResolution.Bound]；
 * 2. 否则 → [SceneResolution.Inherited]：调用方按主对话链路兜底，即
 *    `会话绑定 → 新会话默认模型 → 全局激活供应商`（见 StatefulAgentWorkflow.resolveProviderConfig）；
 * 3. 不允许回退的场景（生图）→ [SceneResolution.Unavailable]，调用方据此给出设置指引。
 *
 * 任何异常都被收敛成结果而不是抛出，避免某个场景配置坏掉把主流程带崩。
 */
@Singleton
class ModelSceneResolver @Inject constructor(
    private val providerRepository: AIProviderRepository,
    private val defaultModel: DefaultModelSettingsRepository,
    private val titleModel: TitleModelSettingsRepository,
    private val compactionModel: CompactionModelSettingsRepository,
    private val visionModel: VisionModelSettingsRepository,
    private val imageGenModel: ImageGenModelSettingsRepository,
) {

    /** 一次读齐所有场景的解析结果（供应商列表只查一次）。 */
    suspend fun resolveAll(): Map<ModelScene, SceneResolution> {
        val providers = loadProviders() ?: return ModelScene.entries
            .associateWith { failure(it, SceneBindingIssue.RESOLVE_FAILED) }
        return ModelScene.entries.associateWith { resolve(it, providers) }
    }

    /** 解析单个场景；读供应商失败时同样走回退，不抛异常。 */
    suspend fun resolve(scene: ModelScene): SceneResolution {
        val providers = loadProviders()
            ?: return failure(scene, SceneBindingIssue.RESOLVE_FAILED)
        return resolve(scene, providers)
    }

    private suspend fun loadProviders(): List<AIProviderConfig>? =
        runCatching { providerRepository.getAllProviders().first() }.getOrNull()

    private suspend fun resolve(
        scene: ModelScene,
        providers: List<AIProviderConfig>,
    ): SceneResolution {
        // 记忆整理没有自己的存储，读的是压缩模型的绑定。
        val source = scene.reusesFrom ?: scene
        val binding = runCatching { readBinding(source) }.getOrElse {
            return failure(scene, SceneBindingIssue.RESOLVE_FAILED)
        }
        if (!binding.isSet) return failure(scene, SceneBindingIssue.NOT_CONFIGURED)

        val provider = providers.firstOrNull { it.id == binding.providerId }
            ?: return failure(scene, SceneBindingIssue.PROVIDER_MISSING)
        if (!provider.isEnabled) {
            return failure(scene, SceneBindingIssue.PROVIDER_DISABLED)
        }
        if (!provider.hasUsableApiKey) {
            return failure(scene, SceneBindingIssue.NO_USABLE_API_KEY)
        }
        return SceneResolution.Bound(
            scene = scene,
            providerId = provider.id,
            providerName = provider.name,
            model = binding.model,
            inheritedFrom = scene.reusesFrom,
        )
    }

    private suspend fun readBinding(scene: ModelScene): SceneBinding = when (scene) {
        ModelScene.MAIN_CHAT -> SceneBinding(
            defaultModel.getDefaultProviderId(),
            defaultModel.getDefaultModel(),
        )
        ModelScene.TITLE -> SceneBinding(titleModel.getTitleProviderId(), titleModel.getTitleModel())
        ModelScene.COMPACTION -> SceneBinding(
            compactionModel.getCompactionProviderId(),
            compactionModel.getCompactionModel(),
        )
        ModelScene.VISION -> SceneBinding(
            visionModel.getVisionProviderId(),
            visionModel.getVisionModel(),
        )
        ModelScene.IMAGE_GEN -> SceneBinding(
            imageGenModel.getImageGenProviderId(),
            imageGenModel.getImageGenModel(),
        )
        // 只能被 reusesFrom 指向，本身没有存储。
        ModelScene.MEMORY -> SceneBinding("", "")
    }
}

/** 无法解析成 Bound 时按场景的可回退性分类，避免调用方各自判断。 */
private fun failure(
    scene: ModelScene,
    issue: SceneBindingIssue,
): SceneResolution = if (scene.fallsBackToMainChat) {
    SceneResolution.Inherited(scene, issue)
} else {
    SceneResolution.Unavailable(scene, issue)
}
