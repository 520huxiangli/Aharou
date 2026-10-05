package com.aharou.feature.editor.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.editor.data.EditorSettings
import com.aharou.feature.editor.data.EditorSettingsRepository
import com.aharou.feature.editor.lsp.ExtensionStatus
import com.aharou.feature.editor.lsp.LanguageExtension
import com.aharou.feature.editor.lsp.LanguageServerInstaller
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 独立编辑器设置页的状态与操作，直接读写 [EditorSettingsRepository]。 */
@HiltViewModel
class EditorSettingsViewModel @Inject constructor(
    private val editorSettings: EditorSettingsRepository,
    private val languageServerInstaller: LanguageServerInstaller
) : ViewModel() {

    val settings: StateFlow<EditorSettings> = editorSettings.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorSettings())

    /** 语言扩展的可用状态：进页面先读缓存、没有才进容器探活；装完刷新。 */
    private val _extensionStatus = MutableStateFlow<Map<LanguageExtension, ExtensionStatus>>(emptyMap())
    val extensionStatus: StateFlow<Map<LanguageExtension, ExtensionStatus>> = _extensionStatus.asStateFlow()

    /** 正在安装的扩展（UI 显示进度），null 表示空闲。 */
    private val _installing = MutableStateFlow<LanguageExtension?>(null)
    val installing: StateFlow<LanguageExtension?> = _installing.asStateFlow()

    init {
        refreshExtensionStatus()
    }

    private fun refreshExtensionStatus() {
        viewModelScope.launch {
            _extensionStatus.value = LanguageExtension.entries.associateWith { extension ->
                languageServerInstaller.cachedStatus(extension)
                    ?: languageServerInstaller.probe(extension)
            }
        }
    }

    /** 安装语言服务器；安装中忽略重复点击。 */
    fun install(extension: LanguageExtension) {
        if (_installing.value != null) return
        viewModelScope.launch {
            _installing.value = extension
            val result = languageServerInstaller.install(extension)
            _extensionStatus.value = _extensionStatus.value + (extension to result.status)
            _installing.value = null
        }
    }

    fun setFontSize(sp: Int) {
        viewModelScope.launch { editorSettings.setFontSize(sp) }
    }

    fun setWordWrap(enabled: Boolean) {
        viewModelScope.launch { editorSettings.setWordWrap(enabled) }
    }

    fun setShowIndentGuide(enabled: Boolean) {
        viewModelScope.launch { editorSettings.setShowIndentGuide(enabled) }
    }

    fun setShowWrapArrow(enabled: Boolean) {
        viewModelScope.launch { editorSettings.setShowWrapArrow(enabled) }
    }

    fun setShowWhitespace(enabled: Boolean) {
        viewModelScope.launch { editorSettings.setShowWhitespace(enabled) }
    }
}
