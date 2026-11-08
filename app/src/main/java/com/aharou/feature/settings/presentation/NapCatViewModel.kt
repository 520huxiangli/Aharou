package com.aharou.feature.settings.presentation

import android.content.Context
import com.aharou.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.im.data.repository.NapCatConfig
import com.aharou.feature.im.data.repository.NapCatSettingsRepository
import com.aharou.feature.im.domain.ImBotBridge
import com.aharou.feature.im.domain.napcat.NapCatRuntimeManager
import com.aharou.feature.im.service.NapCatBotService
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class NapCatViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: NapCatSettingsRepository,
    private val runtime: NapCatRuntimeManager,
    providers: AIProviderRepository,
    workspaces: WorkspaceRepository,
    @param:Named("qq_napcat") private val bridge: ImBotBridge
) : ViewModel() {
    val config = settings.configFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NapCatConfig())
    val providers = providers.getAllProviders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val workspaces = workspaces.workspaces
    val state = runtime.state
    val login = runtime.login
    val connection = bridge.state
    val logs = runtime.logs
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _error = MutableStateFlow("")
    val error = _error.asStateFlow()
    private val _webUrl = MutableStateFlow<String?>(null)
    val webUrl = _webUrl.asStateFlow()

    private fun operate(secret: String = "", block: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        _error.value = ""
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message.orEmpty().ifBlank { e.javaClass.simpleName }
                _error.value = redact(if (secret.isBlank()) message else message.replace(secret, "[redacted]"))
            } finally {
                _busy.value = false
            }
        }
    }

    fun install() = operate { runtime.install() }
    fun update() = operate { runtime.update() }
    fun setEnabled(enabled: Boolean) = operate {
        settings.setEnabled(enabled)
        if (enabled) NapCatBotService.start(context) else NapCatBotService.stop(context)
    }
    fun retryStart() = setEnabled(true)
    fun refreshQrCode() = operate { runtime.refreshQrCode() }
    fun passwordLogin(uin: String, password: String) = operate(secret = password) { runtime.passwordLogin(uin, password) }
    fun logout() = operate { NapCatBotService.logout(context) }
    fun openWebUi() = operate {
        _webUrl.value = runtime.webUiUrl()
        if (_webUrl.value.isNullOrBlank()) _error.value = context.getString(R.string.napcat_webui_unavailable)
    }
    fun closeWebUi() { _webUrl.value = null }
    fun savePolicy(
        privateMessages: Boolean, groups: Boolean, mention: Boolean, self: Boolean,
        users: Set<String>, groupIds: Set<String>
    ) = operate { settings.updatePolicy(privateMessages, groups, mention, self, users, groupIds) }
    fun saveDefaults(workspace: String, provider: String, model: String) = operate {
        settings.setSessionDefaults(workspace.trim(), provider.trim(), model.trim())
    }

    fun redact(text: String): String {
        val cfg = config.value
        var value = text
        listOf(cfg.token, cfg.webUiToken, login.value.nickname).filter { it.isNotBlank() }.forEach {
            value = value.replace(it, "[redacted]")
        }
        value = value.replace(Regex("(?i)(bearer\\s+)[^\\s,;]+"), "$1[redacted]")
        value = value.replace(Regex("(?i)((?:token|password|passwd|secret|authorization|qrCode)[\\\"']?\\s*[=:]\\s*[\\\"']?)[^\\s,;]+"), "$1[redacted]")
        value = value.replace(Regex("https?://[^\\s]+"), "[URL redacted]")
        return value.replace(Regex("(?<!\\d)\\d{5,12}(?!\\d)"), "[account redacted]")
    }
    fun redactedLogs(): String = logs.value.joinToString("\n") { redact(it) }
}
