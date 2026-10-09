package com.aharou.feature.widget

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import com.aharou.core.theme.AppThemeColors
import com.aharou.core.theme.AppThemePreset
import com.aharou.core.theme.deriveDynamicThemeColors
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.settings.data.repository.AppThemeMode
import com.aharou.feature.settings.data.repository.ThemeSettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

/** 小组件「最近会话」里的一行。 */
internal data class WidgetSession(
    val id: String,
    val title: String,
    val updatedAt: Long
)

/** 一次渲染所需的全部内容：会话列表 + 配色。 */
internal data class WidgetSnapshot(
    val sessions: List<WidgetSession>,
    val palette: WidgetPalette
)

/**
 * 从 App 主题解析出的扁平配色（ARGB int）。
 *
 * RemoteViews 跑在 Launcher 进程，拿不到 Compose 主题，所以在 App 进程里把当前主题
 * （配色预设 / 莫奈取色 / 深浅色）换算成具体颜色，再逐个下发。
 */
internal data class WidgetPalette(
    /** 组件背景（页面底色）。 */
    val background: Int,
    /** 主文本（会话标题）。 */
    val onSurface: Int,
    /** 次级文本（时间、空态）。 */
    val onSurfaceVariant: Int,
    /** 主色（「新建对话」按钮底色）。 */
    val primary: Int,
    /** 主色上的文字色。 */
    val onPrimary: Int,
    /** 次级按钮底色（「打开聊天」「语音通话」）。 */
    val chip: Int,
    /** 分隔线颜色。 */
    val divider: Int
)

/**
 * 小组件所需的 Hilt 依赖入口。
 *
 * AppWidgetProvider 由系统实例化，无法构造注入；这里用 EntryPoint 从 Application 容器里
 * 取 DAO 与主题仓库（两者均已由既有 Hilt 模块以 Singleton 提供）。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WidgetEntryPoint {
    fun chatSessionDao(): ChatSessionDao
    fun themeSettingsRepository(): ThemeSettingsRepository
}

/** 小组件的数据与配色来源。所有读取失败都由调用方降级处理，不抛出。 */
internal object WidgetContent {

    /** 展示的最近会话条数，与布局里预置的行槽位数量一致。 */
    const val MAX_SESSIONS = 5

    /** 最近更新的根会话（跨工作区），最多 [MAX_SESSIONS] 条。 */
    suspend fun loadSessions(context: Context): List<WidgetSession> {
        val entry = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        return loadSessions(entry.chatSessionDao())
    }

    private suspend fun loadSessions(dao: ChatSessionDao): List<WidgetSession> =
        dao.getAllOnce()
            .asSequence()
            // 只取根会话，子代理会话不进桌面列表。
            .filter { it.parentId == null }
            .sortedByDescending { it.updatedAt }
            .take(MAX_SESSIONS)
            .map { WidgetSession(id = it.id, title = it.title, updatedAt = it.updatedAt) }
            .toList()

    /**
     * 解析当前主题配色。读取失败（DataStore 异常等）时回退到默认预设，保证组件仍能渲染。
     */
    suspend fun palette(context: Context): WidgetPalette = runCatching {
        val entry = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val repo = entry.themeSettingsRepository()
        val dark = when (repo.themeModeFlow.first()) {
            AppThemeMode.DARK -> true
            AppThemeMode.LIGHT -> false
            AppThemeMode.AUTO -> isSystemDark(context)
        }
        val dynamic = repo.dynamicColorFlow.first()
        if (dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scheme =
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            toPalette(deriveDynamicThemeColors(scheme, dark))
        } else {
            val preset = AppThemePreset.findById(repo.themePresetIdFlow.first())
            toPalette(if (dark) preset.dark else preset.light)
        }
    }.getOrElse {
        val preset = AppThemePreset.DEFAULT
        toPalette(if (isSystemDark(context)) preset.dark else preset.light)
    }

    private fun toPalette(colors: AppThemeColors): WidgetPalette {
        val cs = colors.colorScheme
        val sc = colors.semanticColors
        return WidgetPalette(
            background = sc.pageBackground.toArgb(),
            onSurface = cs.onSurface.toArgb(),
            onSurfaceVariant = cs.onSurfaceVariant.toArgb(),
            primary = cs.primary.toArgb(),
            onPrimary = cs.onPrimary.toArgb(),
            chip = sc.capsuleSurface.toArgb(),
            divider = sc.subtleBorder.toArgb()
        )
    }

    private fun isSystemDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
}
