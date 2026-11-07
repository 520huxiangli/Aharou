package com.aharou.feature.settings.presentation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

internal class UpdateInstallHelper(private val state: MutableStateFlow<UpdateDownloadUiState>) {
    fun prepare(path: String, tag: String, silent: Boolean): UpdateDownloadUiState.Ready? {
        val current = state.value
        if (current is UpdateDownloadUiState.Installing) return null
        val request = UpdateDownloadUiState.Ready(path, tag, UUID.randomUUID().toString())
        state.value = if (silent) UpdateDownloadUiState.Installing(request, true) else request
        return request.takeIf { silent }
    }

    suspend fun install(request: UpdateDownloadUiState.Ready, execute: suspend () -> Boolean) {
        val expected = UpdateDownloadUiState.Installing(request, true)
        if (state.value != expected) return
        val succeeded = try {
            execute()
        } catch (cancelled: CancellationException) {
            state.compareAndSet(expected, UpdateDownloadUiState.Idle)
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!succeeded) state.compareAndSet(expected, request)
    }

    fun claim(request: UpdateDownloadUiState.Ready): String? =
        if (state.compareAndSet(request, UpdateDownloadUiState.Installing(request, false))) request.apkPath else null

    fun returned() {
        val installing = state.value as? UpdateDownloadUiState.Installing ?: return
        if (!installing.silent) state.compareAndSet(installing, installing.request)
    }

    fun failed(request: UpdateDownloadUiState.Ready) {
        state.compareAndSet(UpdateDownloadUiState.Installing(request, false), request)
    }
}
