package com.aharou.feature.editor.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.ContainerProfileResolver
import com.aharou.feature.agent.domain.container.ContainerWorkspaceKeys
import com.aharou.feature.agent.domain.container.RemoteSshConnection
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import com.aharou.feature.settings.data.repository.ExecutionMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class EditorSaveRequest(
    val workspaceRoot: String,
    val workspaceIdentity: String,
    val generation: Long,
    val id: Long,
    val path: String,
    val content: String
)

sealed interface SaveResult {
    val request: EditorSaveRequest
    data class Success(override val request: EditorSaveRequest) : SaveResult
    data class Conflict(override val request: EditorSaveRequest, val diskContent: String) : SaveResult
    data class Error(override val request: EditorSaveRequest, val detail: String?) : SaveResult
}

@Singleton
class EditorWorkspaceIdentity @Inject constructor(
    settings: ContainerSettingsRepository,
    private val profiles: ContainerProfileResolver,
    private val remote: RemoteSshConnection
) {
    private data class Configuration(
        val active: String = ContainerProfile.BUILTIN_ID,
        val custom: List<ContainerProfile> = emptyList(),
        val overrides: Map<String, String> = emptyMap()
    ) {
        fun profile(root: String): ContainerProfile {
            val default = custom.firstOrNull { it.id == active } ?: custom.firstOrNull() ?: ContainerProfile.BUILTIN_ALPINE
            val id = ContainerWorkspaceKeys.overrideFor(overrides, root) ?: return default
            return custom.firstOrNull { it.id == id } ?: default
        }
    }

    private val configuration = combine(settings.activeProfileIdFlow, settings.customProfilesFlow,
        settings.workspaceProfilesFlow) { active, custom, overrides -> Configuration(active, custom, overrides) }
        .stateIn(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), SharingStarted.Eagerly, Configuration())
    val changes = combine(configuration, remote.connectionState) { config, _ ->
        val endpoint = remote.config?.let { listOf(it.host, it.port.toString(), it.username) }
        config.hashCode() to endpoint
    }

    fun key(root: String): String {
        val profile = configuration.value.profile(root)
        val endpoint = if (profile.mode == ExecutionMode.REMOTE_SSH) {
            remote.config?.let { listOf(it.host, it.port.toString(), it.username) }.orEmpty()
        } else emptyList()
        return (listOf(root, profile.id, profile.mode.name) + endpoint).joinToString("|") { "${it.length}:$it" }
    }

    fun matches(root: String, identity: String): Boolean {
        val selected = configuration.value.profile(root)
        val actual = profiles.forWorkspace(root)
        return selected.id == actual.id && selected.mode == actual.mode && key(root) == identity
    }
}

@HiltViewModel
class EditorWorkspaceIdentityViewModel @Inject constructor(val identity: EditorWorkspaceIdentity) : ViewModel() {
    val revisions = identity.changes.scan(0L) { revision, _ -> revision + 1 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
}

@Composable
internal fun rememberEditorWorkspaceIdentity(root: String): String {
    val model: EditorWorkspaceIdentityViewModel = hiltViewModel()
    val revision by model.revisions.collectAsStateWithLifecycle()
    return remember(root, revision) { model.identity.key(root) }
}

internal class EditorSaveState {
    var conflict by mutableStateOf<SaveResult.Conflict?>(null)
    private var leaveRequest: EditorSaveRequest? = null
    private var leave: PendingLeave? = null
    private val discardedSnapshots = mutableSetOf<String>()

    fun discardSnapshot(path: String) { discardedSnapshots.add(path) }

    fun retainSnapshot(path: String): Boolean = !discardedSnapshots.remove(path)

    fun track(request: EditorSaveRequest, pendingLeave: PendingLeave?) {
        leaveRequest = request
        leave = pendingLeave
    }

    fun complete(request: EditorSaveRequest, activePath: String, currentContent: String?): (() -> Unit)? {
        if (leaveRequest != request) return null
        val action = leave?.action?.takeIf {
            request.path == activePath && currentContent == request.content
        }
        clearLeave()
        return action
    }

    fun failed(request: EditorSaveRequest) {
        if (leaveRequest == request) clearLeave()
    }

    fun clearLeave() {
        leaveRequest = null
        leave = null
    }
}
