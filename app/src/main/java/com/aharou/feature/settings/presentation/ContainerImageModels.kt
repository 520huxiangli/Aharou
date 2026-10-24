package com.aharou.feature.settings.presentation

/**
 * 镜像下载的界面状态：空闲 / 下载中 / 完成 / 出错。
 *
 * 从 `SettingsViewModel` 搬出来，与 [ContainerImageController] 放在一起——
 * 这个状态只服务于容器镜像那一个分区。
 */
sealed interface ContainerImageDownloadUiState {
    data object Idle : ContainerImageDownloadUiState

    data class Downloading(
        val entryId: String,
        val name: String,
        val version: String,
        val sourceId: String,
        val bytesRead: Long,
        val totalBytes: Long,
    ) : ContainerImageDownloadUiState

    data class Done(
        val entryId: String,
        val name: String,
        val version: String,
        val fileUri: String,
    ) : ContainerImageDownloadUiState

    data class Error(val entryId: String, val message: String) : ContainerImageDownloadUiState
}
