package com.aharou.feature.agent.domain.skill

import android.content.Context
import com.aharou.core.soul.SoulStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 注入给模型的清单类内容挑译文用的语言来源。
 *
 * 刻意不用 [Locale.getDefault] 判断界面语言：per-app 语言下进程 Locale 未必跟着 App 走，
 * 系统英文 + App 选中文会挑到英文那份。
 */
@Singleton
class AppLanguageProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val promptLanguageSettings: PromptLanguageSettings,
) {
    /** 界面语言代码（`zh` / `en` / …）：提示词语言解析的最终兜底。 */
    val code: String
        get() = context.resources.configuration.locales[0].language

    /**
     * 提示词语言（统一收敛为 `zh` / `en`）：注入给模型的清单类内容（技能描述等）按它挑译文，
     * 提示词正文不受影响。解析优先级：`app.prompt_language` 显式 `zh`/`en` → 用它；
     * `auto` → `soul.lang`（回复语言）非 auto 时用它；两者都是 auto → 界面语言。
     */
    val promptCode: String
        get() {
            val chosen = when (val explicit = promptLanguageSettings.get()) {
                PromptLanguageSettings.ZH, PromptLanguageSettings.EN -> explicit
                else -> SoulStore.cachedMetadata.value.lang.takeIf { it == "zh" || it == "en" }
            }
            return if ((chosen ?: code).startsWith("zh")) "zh" else "en"
        }
}
