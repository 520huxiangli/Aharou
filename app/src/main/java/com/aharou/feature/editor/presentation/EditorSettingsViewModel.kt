package com.aharou.feature.editor.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.editor.data.EditorSettings
import com.aharou.feature.editor.data.EditorSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 独立编辑器设置页的状态与操作，直接读写 [EditorSettingsRepository]。 */
@HiltViewModel
class EditorSettingsViewModel @Inject constructor(
    private val editorSettings: EditorSettingsRepository
) : ViewModel() {

    val settings: StateFlow<EditorSettings> = editorSettings.settingsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorSettings())

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
