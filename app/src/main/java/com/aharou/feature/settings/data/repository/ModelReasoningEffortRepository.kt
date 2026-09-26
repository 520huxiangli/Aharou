package com.aharou.feature.settings.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「模型默认思考强度」记忆：key = "providerId:model"，value = ReasoningEffort.name。
 * 用户在某模型下切换思考强度时写入；新建会话绑定该模型时用它初始化会话档位，
 * 实现模型级档位记忆（类似 opencode 的 variants 选中态按模型存储）。
 */
@Singleton
class ModelReasoningEffortRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取某模型的默认档位 name；未记忆过返回 null。 */
    fun get(providerId: String, model: String): String? =
        prefs.getString(key(providerId, model), null)

    /** 记忆某模型的默认档位 name。 */
    fun set(providerId: String, model: String, effortName: String) {
        prefs.edit().putString(key(providerId, model), effortName).apply()
    }

    private fun key(providerId: String, model: String) = "$providerId:$model"

    private companion object {
        const val PREFS_NAME = "model_reasoning_effort_prefs"
    }
}