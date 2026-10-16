package com.aharou.feature.plugin.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.plugin.domain.PluginManager
import com.aharou.feature.plugin.domain.model.InstalledPlugin
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一次插件操作的结果事件，供 UI 提示；文案由 UI 侧本地化。 */
sealed interface PluginEvent {
    /** 导入并安装成功。 */
    data class Installed(val name: String, val version: String) : PluginEvent

    /** 启动 / 停止 / 卸载成功。 */
    data object Completed : PluginEvent

    /** 失败，[reason] 为诊断信息（异常消息，可能为英文或技术性文字）。 */
    data class Failed(val reason: String) : PluginEvent
}

/**
 * 插件页的状态与动作。独立于 [com.aharou.feature.settings.presentation.SettingsViewModel]
 * （后者体量已到架构门禁上限），操作完成后自行刷新列表。
 */
@HiltViewModel
class PluginViewModel @Inject constructor(
    private val pluginManager: PluginManager,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {

    private val _plugins = MutableStateFlow<List<InstalledPlugin>>(emptyList())
    val plugins: StateFlow<List<InstalledPlugin>> = _plugins.asStateFlow()

    /** 正在处理的插件 id；[IMPORT_BUSY] 表示正在导入。null 表示空闲。 */
    private val _busyId = MutableStateFlow<String?>(null)
    val busyId: StateFlow<String?> = _busyId.asStateFlow()

    private val _event = MutableStateFlow<PluginEvent?>(null)
    val event: StateFlow<PluginEvent?> = _event.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _plugins.value = pluginManager.listInstalled()
        }
    }

    /** 导入所选 `.txplugin` 文件并立即安装。 */
    fun importAndInstall(uri: Uri) {
        if (_busyId.value != null) return
        viewModelScope.launch {
            _busyId.value = IMPORT_BUSY
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val tmp = File(context.cacheDir, "plugin-import.txplugin")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    } ?: error("openInputStream returned null")
                    val manifest = pluginManager.importPackage(tmp).getOrThrow()
                    pluginManager.install(manifest.id).getOrThrow()
                    manifest
                }
            }
            _busyId.value = null
            _event.value = result.fold(
                onSuccess = { PluginEvent.Installed(it.name, it.version) },
                onFailure = { PluginEvent.Failed(it.message ?: it::class.java.simpleName) },
            )
            refresh()
        }
    }

    fun launch(id: String) = operate(id) { pluginManager.launch(it) }

    fun stop(id: String) = operate(id) { pluginManager.stop(it) }

    fun uninstall(id: String) = operate(id) { pluginManager.uninstall(it) }

    fun consumeEvent() {
        _event.value = null
    }

    private fun operate(id: String, block: suspend (String) -> Result<Unit>) {
        if (_busyId.value != null) return
        viewModelScope.launch {
            _busyId.value = id
            val result = block(id)
            _busyId.value = null
            _event.value = result.fold(
                onSuccess = { PluginEvent.Completed },
                onFailure = { PluginEvent.Failed(it.message ?: it::class.java.simpleName) },
            )
            refresh()
        }
    }

    companion object {
        /** 导入中使用的占位 id（不与任何真实插件 id 冲突）。 */
        const val IMPORT_BUSY = "__import__"
    }
}
