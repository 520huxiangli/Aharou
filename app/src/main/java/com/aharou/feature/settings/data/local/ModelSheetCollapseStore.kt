package com.aharou.feature.settings.data.local

import android.content.Context

/**
 * 模型选择弹窗「按提供商折叠」的展开状态持久化：记住用户折叠了哪些提供商，重开弹窗与重启后保持。
 *
 * 存的是折叠集合（默认空 = 全部展开）；提供商删除后残留的 id 无副作用。搜索时的强制展开不写入此状态。
 */
class ModelSheetCollapseStore(context: Context) {

    private val prefs = context.getSharedPreferences("model_sheet_collapse", Context.MODE_PRIVATE)

    fun collapsedProviderIds(): Set<String> =
        prefs.getStringSet(KEY, null)?.toSet() ?: emptySet()

    /** 传新集合副本写入：SharedPreferences 禁止复用已存实例。 */
    fun save(collapsedProviderIds: Set<String>) {
        prefs.edit().putStringSet(KEY, HashSet(collapsedProviderIds)).apply()
    }

    private companion object {
        const val KEY = "collapsed_provider_ids"
    }
}