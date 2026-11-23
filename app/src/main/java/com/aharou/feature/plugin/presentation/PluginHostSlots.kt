package com.aharou.feature.plugin.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.feature.plugin.domain.InstalledPlugin
import com.aharou.feature.plugin.domain.LocalizedText
import com.aharou.feature.plugin.domain.PluginPage
import com.aharou.feature.plugin.domain.PluginUiEntry
import com.aharou.feature.plugin.domain.PluginUiSlots

/**
 * 由 `screen` 入口打开的 Compose DSL 面板。
 *
 * 只带清单里声明过的信息；插件版本与运行时在打开面板时从插件列表现查，避免入口缓存与
 * 重新安装后的版本错位。
 */
data class PluginDslPage(
    val pluginId: String,
    val localId: String,
    val title: LocalizedText,
    val screen: String
) {
    val key: String get() = "$pluginId:$localId"
}

/** 各处插件槽位的入口；从已加载的插件快照算，不读盘。 */
data class PluginHostSlotEntries(
    val drawer: List<PluginUiEntry>,
    val header: List<PluginUiEntry>,
    val editor: List<PluginUiEntry>
)

/** 插件在宿主界面上的入口与当前打开的页面，一次装配后由各处复用。 */
class PluginHostState(
    val slots: PluginHostSlotEntries,
    val languageTag: String,
    val viewModel: PluginsViewModel,
    private val pageState: MutableState<PluginPage?>,
    private val dslState: MutableState<PluginDslPage?>,
    private val toolboxState: MutableState<Boolean>
) {
    val page: PluginPage? get() = pageState.value

    val dslPage: PluginDslPage? get() = dslState.value

    val toolboxVisible: Boolean get() = toolboxState.value

    fun openToolbox() {
        toolboxState.value = true
    }

    fun closeToolbox() {
        toolboxState.value = false
    }

    /** 页面与面板同时只开一个：先关掉另一种，避免两个覆盖层叠在一起。 */
    fun open(entry: PluginUiEntry) {
        val page = entry.page
        if (page != null) {
            dslState.value = null
            pageState.value = page
            return
        }
        val screen = entry.screen
        if (screen != null) {
            pageState.value = null
            dslState.value = PluginDslPage(
                pluginId = entry.pluginId,
                localId = entry.localId,
                title = entry.title,
                screen = screen
            )
        }
    }

    fun close() {
        pageState.value = null
        dslState.value = null
    }
}

@Composable
internal fun rememberPluginHostState(): PluginHostState {
    val viewModel: PluginsViewModel = hiltViewModel()
    val plugins by viewModel.plugins.collectAsStateWithLifecycle()
    val languageTag = currentLanguageTag()
    val slots = rememberPluginSlotEntries(plugins, languageTag)
    val pageState = remember { mutableStateOf<PluginPage?>(null) }
    val dslState = remember { mutableStateOf<PluginDslPage?>(null) }
    val toolboxState = remember { mutableStateOf(false) }
    return remember(viewModel, slots, languageTag) {
        PluginHostState(slots, languageTag, viewModel, pageState, dslState, toolboxState)
    }
}

@Composable
private fun rememberPluginSlotEntries(
    plugins: List<InstalledPlugin>,
    languageTag: String
): PluginHostSlotEntries = remember(plugins, languageTag) {
    val all = plugins.filter { it.isActive }
        .sortedBy { it.displayName(languageTag) }
        .flatMap { plugin ->
            plugin.content.uiEntries.map { entry ->
                entry.copy(page = entry.page?.copy(pluginVersion = plugin.record.version))
            }
        }
    PluginHostSlotEntries(
        drawer = all.filter { it.slot == PluginUiSlots.DRAWER_FOOTER },
        header = all.filter { it.slot == PluginUiSlots.CHAT_HEADER_MENU },
        editor = all.filter { it.slot == PluginUiSlots.EDITOR_SELECTION_MENU }
    )
}

/** 插件页面/面板全屏覆盖层：页面与面板各自带关闭入口，系统返回键也关。 */
@Composable
internal fun PluginHostOverlay(host: PluginHostState, sessionId: () -> String?) {
    val page = host.page
    val dslPage = host.dslPage
    when {
        page != null -> Dialog(
            onDismissRequest = host::close,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            PluginPageHost(
                page = page,
                languageTag = host.languageTag,
                pluginsViewModel = host.viewModel,
                onClose = host::close,
                context = { PluginEntryContext(sessionId = sessionId()) }
            )
        }

        dslPage != null -> Dialog(
            onDismissRequest = host::close,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            PluginDslPanelScreen(
                page = dslPage,
                languageTag = host.languageTag,
                viewModel = host.viewModel,
                onClose = host::close
            )
        }
    }
}
