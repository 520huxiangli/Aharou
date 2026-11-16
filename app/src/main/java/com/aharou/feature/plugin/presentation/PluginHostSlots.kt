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
import com.aharou.feature.plugin.domain.PluginPage
import com.aharou.feature.plugin.domain.PluginUiEntry
import com.aharou.feature.plugin.domain.PluginUiSlots

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
    private val toolboxState: MutableState<Boolean>
) {
    val page: PluginPage? get() = pageState.value

    val toolboxVisible: Boolean get() = toolboxState.value

    fun openToolbox() {
        toolboxState.value = true
    }

    fun closeToolbox() {
        toolboxState.value = false
    }

    fun open(entry: PluginUiEntry) {
        entry.page?.let { pageState.value = it }
    }

    fun close() {
        pageState.value = null
    }
}

@Composable
internal fun rememberPluginHostState(): PluginHostState {
    val viewModel: PluginsViewModel = hiltViewModel()
    val plugins by viewModel.plugins.collectAsStateWithLifecycle()
    val languageTag = currentLanguageTag()
    val slots = rememberPluginSlotEntries(plugins, languageTag)
    val pageState = remember { mutableStateOf<PluginPage?>(null) }
    val toolboxState = remember { mutableStateOf(false) }
    return remember(viewModel, slots, languageTag) {
        PluginHostState(slots, languageTag, viewModel, pageState, toolboxState)
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

/** 插件页面全屏覆盖层：页面自带关闭按钮，系统返回键也关。 */
@Composable
internal fun PluginHostOverlay(host: PluginHostState, sessionId: () -> String?) {
    val current = host.page ?: return
    Dialog(
        onDismissRequest = host::close,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        PluginPageHost(
            page = current,
            languageTag = host.languageTag,
            pluginsViewModel = host.viewModel,
            onClose = host::close,
            context = { PluginEntryContext(sessionId = sessionId()) }
        )
    }
}
