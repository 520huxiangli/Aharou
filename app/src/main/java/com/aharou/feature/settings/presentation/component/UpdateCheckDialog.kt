package com.aharou.feature.settings.presentation.component

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.settings.data.remote.UpdateDownloadSource
import com.aharou.feature.settings.presentation.UpdateCheckUiState
import com.aharou.feature.settings.presentation.UpdateDownloadUiState
import java.io.File

/**
 * 检查更新结果弹窗（全局宿主渲染，自动/手动共用）。
 * 新版本弹窗列出从当前版本到最新版本的更新日志，并支持**应用内下载**安装包：
 * 进度与当前下载来源就地展示，下完自动交给系统安装器；所有来源都失败时才给浏览器兜底出口。
 */
@Composable
internal fun UpdateCheckDialog(
    state: UpdateCheckUiState,
    downloadState: UpdateDownloadUiState,
    currentVersion: String,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: (apkPath: String) -> Unit,
    onOpenRelease: (tag: String) -> Unit
) {
    when (state) {
        UpdateCheckUiState.Checking -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.about_check_update)) },
            text = { Text(stringResource(R.string.about_checking_update)) },
            confirmButton = {}
        )
        UpdateCheckUiState.UpToDate -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.about_up_to_date)) },
            text = { Text(stringResource(R.string.about_up_to_date_detail, currentVersion)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_got_it)) } }
        )
        is UpdateCheckUiState.NewVersion -> NewVersionDialog(
            state = state,
            downloadState = downloadState,
            onDismiss = onDismiss,
            onDownload = onDownload,
            onCancelDownload = onCancelDownload,
            onInstall = onInstall,
            onOpenRelease = onOpenRelease
        )
        is UpdateCheckUiState.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.about_check_failed)) },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.about_ok)) } }
        )
        UpdateCheckUiState.Idle -> {}
    }
}

@Composable
private fun NewVersionDialog(
    state: UpdateCheckUiState.NewVersion,
    downloadState: UpdateDownloadUiState,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: (apkPath: String) -> Unit,
    onOpenRelease: (tag: String) -> Unit
) {
    // 下载完成即拉起系统安装器；key 用产物路径，保证同一份包只自动弹一次。
    val ready = downloadState as? UpdateDownloadUiState.Ready
    LaunchedEffect(ready?.apkPath) {
        ready?.let { onInstall(it.apkPath) }
    }
    val busy = downloadState is UpdateDownloadUiState.Resolving ||
        downloadState is UpdateDownloadUiState.Downloading

    AlertDialog(
        // 下载中不允许点外部消失，避免误触把进行中的下载丢掉
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.about_new_version_found)) },
        text = {
            Column {
                Text(
                    text = state.changelog,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when (downloadState) {
                    UpdateDownloadUiState.Resolving -> {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.about_download_resolving),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is UpdateDownloadUiState.Downloading -> {
                        Spacer(Modifier.height(12.dp))
                        val percent = if (downloadState.totalBytes > 0) {
                            (downloadState.bytesRead * 100 / downloadState.totalBytes).toInt()
                        } else {
                            -1
                        }
                        val source = downloadSourceLabel(downloadState.sourceUrl)
                        Text(
                            text = if (percent >= 0) {
                                stringResource(R.string.about_downloading_from, source) + " · $percent%"
                            } else {
                                stringResource(R.string.about_downloading_from, source)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                        if (downloadState.totalBytes > 0) {
                            LinearProgressIndicator(
                                progress = {
                                    (downloadState.bytesRead.toFloat() / downloadState.totalBytes)
                                        .coerceIn(0f, 1f)
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    is UpdateDownloadUiState.Ready -> {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.about_download_installing),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.about_download_install_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is UpdateDownloadUiState.Failed -> {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.about_download_failed) +
                                downloadState.message?.let { "：$it" }.orEmpty(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    UpdateDownloadUiState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            when {
                busy -> TextButton(onClick = onCancelDownload) {
                    Text(stringResource(R.string.about_download_cancel))
                }
                downloadState is UpdateDownloadUiState.Ready -> TextButton(
                    onClick = { onInstall(downloadState.apkPath) }
                ) { Text(stringResource(R.string.about_download_install_now)) }
                else -> TextButton(onClick = onDownload) {
                    Text(stringResource(R.string.about_download_and_install))
                }
            }
        },
        dismissButton = {
            if (downloadState is UpdateDownloadUiState.Failed) {
                // 应用内下载全挂了才让用户去浏览器
                TextButton(onClick = { onOpenRelease(state.latestTag) }) {
                    Text(stringResource(R.string.about_open_in_browser))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.about_later)) }
            }
        }
    )
}

/** 下载来源 → 面向用户的标签。 */
@Composable
private fun downloadSourceLabel(url: String): String = when {
    url.contains("gitcode.com") -> stringResource(R.string.about_download_source_gitcode)
    url.startsWith("https://github.com/") -> stringResource(R.string.about_download_source_github)
    else -> stringResource(R.string.about_download_source_mirror)
}

/** 用隐式 Intent 打开浏览器，捕获异常避免崩溃。 */
internal fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

/**
 * 把下好的安装包交给系统安装器。
 *
 * 必须走 FileProvider 授权 URI：targetSdk 28+ 直接用 file:// 会抛 FileUriExposedException。
 * 未开启「安装未知应用」时由系统安装器自行引导，这里不预判。
 */
internal fun installDownloadedApk(context: Context, apkPath: String) {
    runCatching {
        val file = File(apkPath)
        if (!file.exists()) error("安装包不存在")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }.onFailure { FileLogger.w("UpdateInstall", "install failed: ${it.message}") }
}

/**
 * 指定版本 tag 的 GitHub Release 页面地址；tag 为空时回退到最新正式版页面。
 *
 * GitHub 的 `releases/latest` 只解析到最新的**正式** release（预发布不计入），最新版通道
 * 检测到 RC 时跳过去会看到旧的稳定版，因此必须按 tag 直达对应版本页面。
 */
internal fun githubReleaseUrl(tag: String?): String = UpdateDownloadSource.releasePage(tag)
