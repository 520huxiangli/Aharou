package com.aharou.feature.settings.data

import android.content.Context
import android.net.Uri
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.fields.DataStoreBoolField
import com.aharou.core.config.fields.DataStoreDoubleField
import com.aharou.core.config.fields.DataStoreEnumField
import com.aharou.core.config.fields.DataStoreIntField
import com.aharou.core.config.fields.DataStoreStrField
import com.aharou.core.util.LogLevel
import com.aharou.feature.editor.data.EditorSettingsRepository
import com.aharou.feature.settings.data.repository.AgentSoundSettingsRepository
import com.aharou.feature.settings.data.repository.AppThemeMode
import com.aharou.feature.settings.data.repository.BackgroundSettingsRepository
import com.aharou.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aharou.feature.settings.data.repository.LogSettingsRepository
import com.aharou.feature.settings.data.repository.ScreenOnSettingsRepository
import com.aharou.feature.settings.data.repository.ThemeSettingsRepository
import com.aharou.feature.terminal.data.repository.TerminalSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 界面外观 / 编辑器 / 终端 / 声音 / 前台保活 → 配置通道。
 *
 * 枚举类设置统一用 `.name.lowercase()` 进出（[DataStoreEnumField] 的 cases 也是小写），
 * 这样 Agent 侧的取值与 `SettingsScreen` 里的英文名一致，不受枚举重命名影响。
 */
@Singleton
class ConfigAppearanceFields @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val themeSettings: ThemeSettingsRepository,
    private val backgroundSettings: BackgroundSettingsRepository,
    private val editorSettings: EditorSettingsRepository,
    private val terminalSettings: TerminalSettingsRepository,
    private val agentSoundSettings: AgentSoundSettingsRepository,
    private val keepaliveSettings: KeepaliveSettingsRepository,
    private val screenOnSettings: ScreenOnSettingsRepository,
    private val logSettings: LogSettingsRepository,
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(
            DataStoreEnumField(
                path = "appearance.theme_mode",
                displayName = "主题模式",
                description = "深浅色：auto=跟随系统 / dark=深色 / light=浅色。",
                flow = themeSettings.themeModeFlow.map { it.name.lowercase() },
                setter = { themeSettings.setThemeMode(AppThemeMode.valueOf(it.uppercase())) },
                cases = listOf("auto", "dark", "light"),
            ),
        )

        registry.register(
            DataStoreStrField(
                path = "appearance.theme_preset",
                displayName = "主题预设",
                description = "配色方案 id；空 = 用默认预设。可用值见设置页「外观」里的预设列表。",
                flow = themeSettings.themePresetIdFlow.map { it.orEmpty() },
                setter = { themeSettings.setThemePresetId(it) },
            ),
        )

        registry.register(
            DataStoreBoolField(
                path = "appearance.dynamic_color",
                displayName = "动态取色",
                description = "跟随壁纸取色（Android 12+）。",
                flow = themeSettings.dynamicColorFlow,
                setter = { themeSettings.setDynamicColorEnabled(it) },
            ),
        )

        registry.register(
            DataStoreStrField(
                path = "appearance.background_image",
                displayName = "聊天背景图",
                description = "背景图片的 URI（content:// 或 file://）；空 = 无背景。",
                flow = backgroundSettings.imagePathFlow.map { it.orEmpty() },
                setter = { path ->
                    if (path.isBlank()) throw ConfigError.InvalidValue("背景图 URI 不能为空")
                    backgroundSettings.setBackgroundImage(Uri.parse(path))
                },
            ),
        )

        registry.register(
            DataStoreDoubleField(
                path = "appearance.background_alpha",
                displayName = "背景不透明度",
                description = "背景图不透明度，0.0（全透明）~ 1.0（不透明）。默认 0.15。",
                flow = backgroundSettings.alphaFlow,
                setter = { backgroundSettings.setBackgroundAlpha(it) },
                minValue = 0.0,
                maxValue = 1.0,
            ),
        )

        registry.register(
            DataStoreIntField(
                path = "editor.font_size_sp",
                displayName = "编辑器字号（sp）",
                description = "代码编辑器字号。默认 14。",
                flow = editorSettings.settingsFlow.map { it.fontSizeSp },
                setter = { editorSettings.setFontSize(it) },
            ),
        )

        registry.register(
            DataStoreBoolField(
                path = "editor.word_wrap",
                displayName = "编辑器自动换行",
                description = "代码编辑器是否折行显示。默认关闭。",
                flow = editorSettings.settingsFlow.map { it.wordWrap },
                setter = { editorSettings.setWordWrap(it) },
            ),
        )

        registry.register(
            DataStoreBoolField(
                path = "editor.show_indent_guide",
                displayName = "缩进参考线",
                description = "编辑器里显示缩进对齐竖线。默认开启。",
                flow = editorSettings.settingsFlow.map { it.showIndentGuide },
                setter = { editorSettings.setShowIndentGuide(it) },
            ),
        )

        registry.register(
            DataStoreBoolField(
                path = "editor.show_wrap_arrow",
                displayName = "换行箭头",
                description = "折行处显示回车箭头标记。默认关闭。",
                flow = editorSettings.settingsFlow.map { it.showWrapArrow },
                setter = { editorSettings.setShowWrapArrow(it) },
            ),
        )

        registry.register(
            DataStoreBoolField(
                path = "editor.show_whitespace",
                displayName = "显示空白字符",
                description = "把空格 / 制表符渲染成可见符号。默认关闭。",
                flow = editorSettings.settingsFlow.map { it.showWhitespace },
                setter = { editorSettings.setShowWhitespace(it) },
            ),
        )

        registry.register(
            DataStoreStrField(
                path = "terminal.theme_id",
                displayName = "终端配色",
                description = "终端主题预设 id，如 termius_dark。可用值见设置页「终端」里的配色列表。",
                flow = terminalSettings.settingsFlow.map { it.themeId },
                setter = { terminalSettings.setThemeId(it) },
            ),
        )

        registry.register(
            DataStoreIntField(
                path = "terminal.font_size_sp",
                displayName = "终端字号（sp）",
                description = "终端字号。默认 12。",
                flow = terminalSettings.settingsFlow.map { it.fontSizeSp },
                setter = { terminalSettings.setFontSizeSp(it) },
            ),
        )

        registry.register(
            DataStoreIntField(
                path = "terminal.cursor_style",
                displayName = "终端光标样式",
                description = "0=方块 / 1=下划线 / 2=竖线。默认 0。",
                flow = terminalSettings.settingsFlow.map { it.cursorStyle },
                setter = { terminalSettings.setCursorStyle(it) },
                minValue = 0,
                maxValue = 2,
            ),
        )

        registry.register(
            DataStoreStrField(
                path = "terminal.font_path",
                displayName = "终端字体路径",
                description = "终端字体标识：空 = 系统等宽；内置 JetBrains Mono NL 用其标识；导入字体为绝对路径。",
                flow = terminalSettings.settingsFlow.map { it.fontPath },
                setter = { terminalSettings.setFontPath(it) },
            ),
        )

        registry.register(
            DataStoreBoolField(
                path = "sound.agent_enabled",
                displayName = "AI 提示音",
                description = "AI 回复完成时播放提示音。",
                flow = agentSoundSettings.enabledFlow,
                setter = { agentSoundSettings.setEnabled(it) },
            ),
        )

        registry.register(
            DataStoreBoolField(
                path = "app.keepalive",
                displayName = "终端保活",
                description = "启动前台服务维持终端会话，降低被系统回收的概率。",
                flow = keepaliveSettings.enabledFlow,
                setter = { keepaliveSettings.setEnabled(it) },
            ),
        )

        registry.register(
            DataStoreBoolField(
                path = "app.screen_on",
                displayName = "屏幕常亮",
                description = "AI 工作期间保持屏幕常亮，不自动息屏。",
                flow = screenOnSettings.enabledFlow,
                setter = { screenOnSettings.setEnabled(it) },
            ),
        )

        registry.register(
            DataStoreEnumField(
                path = "app.log_level",
                displayName = "日志等级",
                description = "写入日志文件的最低等级：verbose / debug / info / warn / error / none（none = 全关）。",
                flow = logSettings.levelFlow.map { it.name.lowercase() },
                setter = { logSettings.setLevel(LogLevel.valueOf(it.uppercase())) },
                cases = listOf("verbose", "debug", "info", "warn", "error", "none"),
            ),
        )
    }
}
