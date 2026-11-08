package com.aharou.feature.im.domain

import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class ImWorkspaceResolver @Inject constructor(
    private val workspaces: WorkspaceRepository
) {
    private val mutex = Mutex()

    suspend fun fallbackPath(): String = mutex.withLock {
        workspaces.refreshAvailability()
        val existing = workspaces.workspaces.value.firstOrNull { it.name == NAME && it.available }
        val workspace = existing ?: workspaces.createWorkspace(NAME)
            ?: workspaces.workspaces.value.firstOrNull { it.name == NAME && it.available }
        checkNotNull(workspace) { "Unable to create the robot workspace" }.path
    }

    private companion object { const val NAME = "im-robots" }
}
