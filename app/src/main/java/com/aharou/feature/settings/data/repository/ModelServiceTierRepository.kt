package com.aharou.feature.settings.data.repository

import android.content.Context
import com.aharou.feature.settings.domain.model.ServiceTier
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「模型服务档位」记忆：key = "providerId:model"，value = ServiceTier.name。
 *
 * 与思考强度同一套模型级记忆口径：用户按模型单独选，不挂在供应商上——
 * 同一个中转站在不同模型上支持的档位并不一致。没有记录就是「跟随上游」，请求里不带
 * `service_tier`。
 */
@Singleton
class ModelServiceTierRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取某模型记忆的服务档位 name；未记忆过返回 null。 */
    fun get(providerId: String, model: String): String? =
        prefs.getString(key(providerId, model), null)

    /** 记忆某模型的服务档位 name。 */
    fun set(providerId: String, model: String, tierName: String) {
        prefs.edit().putString(key(providerId, model), tierName).apply()
    }

    /** 清掉某模型的记忆，回到「跟随上游」。 */
    fun clear(providerId: String, model: String) {
        prefs.edit().remove(key(providerId, model)).apply()
    }

    /** 直接给出该模型应发送的 service_tier 值；未记忆、或选了「跟随上游」时返回 null（不发该字段）。 */
    fun apiValueFor(providerId: String, model: String): String? =
        ServiceTier.fromName(get(providerId, model))?.apiValue

    private fun key(providerId: String, model: String) = "$providerId:$model"

    private companion object {
        const val PREFS_NAME = "model_service_tier_prefs"
    }
}
