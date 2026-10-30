package com.aharou.feature.agent.domain.provider

import com.aharou.core.util.FileLogger
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 跨供应商回退的会话级状态。
 *
 * 之所以按会话粘住而不是「失败就切一次」：主供应商额度耗尽后，后续每一轮都会先撞一次失败
 * （白等一次首字超时），粘住之后直接走备用，直到备用自己也不可用或用户在设置里改动。
 *
 * 抽成独立类还有个现实原因：[com.aharou.feature.agent.domain.workflow.StatefulAgentWorkflow]
 * 有单文件行数棘轮，逻辑放外面才不会让门禁失败。
 */
@Singleton
class ProviderFailoverCoordinator @Inject constructor(
    private val providerRepository: AIProviderRepository
) {

    /** sessionKey → (回退供应商 id, 回退模型)；模型为空串表示用目标供应商自己的当前模型。 */
    private val overrides = ConcurrentHashMap<String, Pair<String, String>>()

    private fun sessionKey(sessionId: String?): String =
        sessionId?.takeIf { it.isNotBlank() } ?: NO_SESSION

    /** 该会话当前是否已切在回退供应商上。 */
    fun isActive(sessionId: String?): Boolean = overrides.containsKey(sessionKey(sessionId))

    /** 已激活时返回目标配置；目标被删/禁用/失 Key 就顺手清掉覆盖。 */
    suspend fun overrideConfig(sessionId: String?): AIProviderConfig? {
        val key = sessionKey(sessionId)
        val (providerId, model) = overrides[key] ?: return null
        val config = providerRepository.getProviderById(providerId)
        if (config == null || !config.isEnabled || !config.hasUsableApiKey || config.effectiveModel.isBlank()) {
            overrides.remove(key)
            return null
        }
        return if (model.isNotBlank() && model in config.models) config.copy(selectedModel = model) else config
    }

    /**
     * 按 [origin] 上配置的回退关系激活一次，返回切到的目标配置。
     * 不配、指向自己、目标不存在/禁用/无 Key/无模型时返回 null，调用方按原错误处理。
     */
    suspend fun activate(sessionId: String?, origin: AIProviderConfig): AIProviderConfig? {
        val fallbackId = origin.fallbackProviderId.trim()
        if (fallbackId.isBlank() || fallbackId == origin.id) return null
        val target = providerRepository.getProviderById(fallbackId) ?: return null
        if (!target.isEnabled || !target.hasUsableApiKey) return null
        // 回退模型可以不在目标的模型列表里：用户可能手填了新模型，交给请求本身报错，不在这里拦
        val model = origin.fallbackModel.trim().ifBlank { target.effectiveModel }
        if (model.isBlank()) return null
        overrides[sessionKey(sessionId)] = target.id to model
        FileLogger.w(TAG, "provider 回退：${origin.name} -> ${target.name}($model)")
        return target.copy(selectedModel = model)
    }

    /** 回退目标自己也撑不住：撤销覆盖，下一轮回到主供应商，不再反复弹同一条错误。 */
    fun deactivate(sessionId: String?) {
        overrides.remove(sessionKey(sessionId))
    }

    private companion object {
        const val TAG = "ProviderFailover"
        const val NO_SESSION = "__no_session__"
    }
}
