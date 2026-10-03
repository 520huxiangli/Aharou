package com.aharou.feature.agent.domain.skill

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App 内选中的界面语言（`zh` / `en` …），供技能描述这类带多语言字段的内容挑选译文。
 *
 * 刻意不用 [Locale.getDefault]：per-app 语言下进程 Locale 未必跟着 App 走，
 * 系统英文 + App 选中文会挑到英文那份。
 */
@Singleton
class AppLanguageProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    val code: String
        get() = context.resources.configuration.locales[0].language
}
