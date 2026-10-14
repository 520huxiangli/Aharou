package com.aharou.feature.agent.domain.skill

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 提示词语言的持久化（`auto` / `zh` / `en`）。
 *
 * 提示词语言决定注入给模型的**清单类内容**（技能描述等）按哪份译文挑选，与 `soul.lang`（回复语言）、
 * 界面语言是三根相互独立的轴。用 SharedPreferences 而非 DataStore：提示词组装的读取发生在同步路径上
 * （系统提示词构建、技能清单生成），需要即时读到最新值。配置通道字段 `app.prompt_language` 共用这份存储。
 */
@Singleton
class PromptLanguageSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 当前取值（`auto` / `zh` / `en`）；存量非法值一律回退 [AUTO]。 */
    fun get(): String = prefs().getString(KEY, null)?.takeIf { it == ZH || it == EN } ?: AUTO

    fun set(tag: String) {
        prefs().edit().putString(KEY, tag).apply()
    }

    companion object {
        const val AUTO = "auto"
        const val ZH = "zh"
        const val EN = "en"

        /** 与配置字段 `app.prompt_language` 的取值保持同一套。 */
        val VALUES = listOf(AUTO, ZH, EN)

        private const val PREFS_NAME = "prompt_language_prefs"
        private const val KEY = "prompt_language"
    }
}
