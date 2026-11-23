package com.aharou.feature.plugin.presentation

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.feature.plugin.data.market.OperitMarketListing
import com.aharou.feature.plugin.workfloweditor.WorkflowEditorScreen
import com.aharou.feature.plugin.workfloweditor.WorkflowListScreen
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronLeft
import compose.icons.feathericons.GitBranch
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Trash2

/** 插件面板内的页面层级；设置页只挂一个入口，层级切换在本组件内部完成。 */
private enum class PluginHostPage { List, Detail, StoreDetail, OperitDetail, DevDetail, WorkflowList, WorkflowEditor }

/**
 * 设置 → 插件 的容器：列表/详情/商店/开发者详情四层在这里切换，外层设置页无需为插件增加
 * 导航分支。列表页（[PluginsSection]）自身带已安装/开发中/商店三个分页。
 */
@Composable
internal fun PluginScreenHost(viewModel: PluginsViewModel = hiltViewModel()) {
    val plugins by viewModel.plugins.collectAsStateWithLifecycle()
    val publishState by viewModel.publishState.collectAsStateWithLifecycle()
    val publishExport by viewModel.publishExport.collectAsStateWithLifecycle()
    val shareContext = androidx.compose.ui.platform.LocalContext.current
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val dependencyStates by viewModel.dependencyStates.collectAsStateWithLifecycle()
    val shizukuState by viewModel.shizukuState.collectAsStateWithLifecycle()
    val shizukuGranted by viewModel.shizukuGrantedPluginIds.collectAsStateWithLifecycle()
    val overlayGranted by viewModel.overlayGrantedPluginIds.collectAsStateWithLifecycle()
    val backupGranted by viewModel.backupGrantedPluginIds.collectAsStateWithLifecycle()
    val devMode by viewModel.devMode.collectAsStateWithLifecycle()
    val devProjects by viewModel.devProjects.collectAsStateWithLifecycle()
    val devRefreshing by viewModel.devRefreshing.collectAsStateWithLifecycle()
    val indexSourceText by viewModel.indexSourceText.collectAsStateWithLifecycle()
    val downloadMirror by viewModel.downloadMirror.collectAsStateWithLifecycle()

    var page by remember { mutableStateOf(PluginHostPage.List) }
    var selectedPluginId by remember { mutableStateOf<String?>(null) }
    var selectedStoreItem by remember { mutableStateOf<PluginStoreItem?>(null) }
    var selectedOperitItem by remember { mutableStateOf<OperitMarketListing?>(null) }
    var selectedDevProject by remember { mutableStateOf<PluginDevProject?>(null) }
    var editingWorkflowId by remember { mutableStateOf<String?>(null) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showUninstallConfirm by remember { mutableStateOf(false) }

    val languageTag = currentLanguageTag()
    val selectedPlugin = plugins.firstOrNull { it.id == selectedPluginId }
    val currentDevProject = devProjects.firstOrNull { it.dirName == selectedDevProject?.dirName }
        ?: selectedDevProject

    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(viewModel::import)
    }

    Box(Modifier.fillMaxSize()) {
        when (page) {
            PluginHostPage.List -> Column(Modifier.fillMaxSize()) {
                PluginHostToolbar(
                    title = stringResource(R.string.settings_plugins),
                    onBack = null,
                    onSettings = { showSettingsSheet = true },
                    onWorkflows = { page = PluginHostPage.WorkflowList },
                    // 除 Aharou 插件 zip 外还要能选中 Operit 的单文件 JS 与技能 JSON；
                    // 部分文件管理器只把 .js 报成 octet-stream，所以保留宽类型
                    onImport = {
                        zipLauncher.launch(
                            arrayOf(
                                "application/zip",
                                "application/x-zip-compressed",
                                "application/octet-stream",
                                "text/javascript",
                                "application/javascript",
                                "application/json"
                            )
                        )
                    }
                )
                PluginsSection(
                    plugins = plugins,
                    loaded = loaded,
                    devMode = devMode,
                    devProjects = devProjects,
                    devRootPath = viewModel.devRootPath,
                    refreshing = refreshing,
                    devRefreshing = devRefreshing,
                    onRefresh = viewModel::refresh,
                    onRefreshDevProjects = viewModel::refreshDevProjects,
                    onOpenDevDetail = {
                        selectedDevProject = it
                        page = PluginHostPage.DevDetail
                    },
                    onSetDevEnabled = { project, enabled ->
                        val installed = plugins.firstOrNull { it.id == project.pluginId }
                        when {
                            installed != null -> if (installed.status == com.aharou.feature.plugin.domain.PluginStatus.OK) {
                                viewModel.setEnabled(installed.id, enabled)
                            }
                            enabled && project.error == null -> viewModel.installDevProject(project)
                        }
                    },
                    onOpenDetail = {
                        selectedPluginId = it.id
                        page = PluginHostPage.Detail
                    },
                    onToggleEnabled = { plugin, enabled -> viewModel.setEnabled(plugin.id, enabled) },
                    onShowStorePreview = viewModel::showPreview,
                    onOpenStoreDetail = {
                        selectedStoreItem = it
                        page = PluginHostPage.StoreDetail
                    },
                    onOpenOperitDetail = {
                        selectedOperitItem = it
                        page = PluginHostPage.OperitDetail
                    }
                )
            }

            PluginHostPage.Detail -> {
                val plugin = selectedPlugin
                if (plugin == null) {
                    page = PluginHostPage.List
                } else {
                    Column(Modifier.fillMaxSize()) {
                        PluginHostToolbar(
                            title = plugin.displayName(languageTag),
                            onBack = { page = PluginHostPage.List },
                            onUninstall = { showUninstallConfirm = true }
                        )
                        TextButton(
                            onClick = {
                                val exported = publishExport
                                if (exported != null) {
                                    shareSubmission(shareContext, exported)
                                    // 分享完清掉状态，否则按钮会一直停在「分享提交包」上
                                    viewModel.clearPublishState()
                                } else {
                                    viewModel.publish(plugin.id)
                                }
                            }
                        ) {
                            Text(
                                text = when {
                                    publishExport != null -> stringResource(R.string.plugins_publish_share)
                                    publishState == null -> stringResource(R.string.plugins_publish_action)
                                    publishState!!.isEmpty() -> stringResource(R.string.plugins_publish_running)
                                    else -> publishState!!
                                }
                            )
                        }
                        val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                        PluginDetailSection(
                            plugin = plugin,
                            onToggleEnabled = { viewModel.setEnabled(plugin.id, it) },
                            bridgeFactory = { pluginPage, post, onClosePage ->
                                viewModel.createWebBridge(
                                    page = pluginPage,
                                    pluginVersion = pluginPage.pluginVersion,
                                    languageTag = languageTag,
                                    theme = { if (isDark) "dark" else "light" },
                                    onClosePage = onClosePage,
                                    post = post
                                )
                            },
                            onClosePage = { page = PluginHostPage.List },
                            dependencyState = dependencyStates[plugin.id],
                            onPrepareDependencies = { viewModel.prepareDependencies(plugin.id) },
                            shizukuGranted = plugin.id in shizukuGranted,
                            shizukuState = shizukuState,
                            onSetShizukuGranted = { viewModel.setShizukuGranted(plugin, it) },
                            overlayGranted = plugin.id in overlayGranted,
                            onSetOverlayGranted = { viewModel.setOverlayGranted(plugin, it) },
                            backupGranted = plugin.id in backupGranted,
                            onSetBackupGranted = { viewModel.setBackupGranted(plugin, it) }
                        )
                    }
                }
            }

            PluginHostPage.StoreDetail -> {
                val item = selectedStoreItem
                if (item == null) {
                    page = PluginHostPage.List
                } else {
                    Column(Modifier.fillMaxSize()) {
                        PluginHostToolbar(
                            title = item.plugin.name.resolve(languageTag),
                            onBack = { page = PluginHostPage.List }
                        )
                        PluginStoreDetailSection(
                            item = item,
                            onShowPreview = viewModel::showPreview
                        )
                    }
                }
            }

            PluginHostPage.OperitDetail -> {
                val item = selectedOperitItem
                if (item == null) {
                    page = PluginHostPage.List
                } else {
                    Column(Modifier.fillMaxSize()) {
                        PluginHostToolbar(
                            title = item.title,
                            onBack = { page = PluginHostPage.List }
                        )
                        OperitMarketDetailSection(
                            item = item,
                            onShowPreview = viewModel::showPreview
                        )
                    }
                }
            }

            PluginHostPage.WorkflowList -> Column(Modifier.fillMaxSize()) {
                PluginHostToolbar(
                    title = stringResource(R.string.workflow_editor_list_title),
                    onBack = { page = PluginHostPage.List }
                )
                WorkflowListScreen(
                    onOpenWorkflow = { workflowId ->
                        editingWorkflowId = workflowId
                        page = PluginHostPage.WorkflowEditor
                    }
                )
            }

            PluginHostPage.WorkflowEditor -> {
                val workflowId = editingWorkflowId
                if (workflowId == null) {
                    page = PluginHostPage.WorkflowList
                } else {
                    // 编辑器自带标题行（返回/保存/运行/加节点），不再套宿主工具栏，避免两层返回键。
                    WorkflowEditorScreen(
                        workflowId = workflowId,
                        onBack = { page = PluginHostPage.WorkflowList }
                    )
                }
            }

            PluginHostPage.DevDetail -> {
                val project = currentDevProject
                if (project == null) {
                    page = PluginHostPage.List
                } else {
                    Column(Modifier.fillMaxSize()) {
                        PluginHostToolbar(
                            title = project.name?.resolve(languageTag) ?: project.dirName,
                            onBack = { page = PluginHostPage.List }
                        )
                        PluginDevDetailSection(
                            project = project,
                            plugins = plugins,
                            devRootPath = viewModel.devRootPath,
                            onInstall = { viewModel.installDevProject(project) }
                        )
                    }
                }
            }
        }
    }

    PluginImportHost(viewModel)

    if (showSettingsSheet) {
        PluginSettingsSheet(
            devMode = devMode,
            devRootPath = viewModel.devRootPath,
            indexSourceText = indexSourceText,
            downloadMirror = downloadMirror,
            onSetIndexSourceText = viewModel::setIndexSourceText,
            onSetDownloadMirror = viewModel::setDownloadMirror,
            onSetDevMode = viewModel::setDevMode,
            onDismiss = {
                viewModel.savePluginSettings()
                showSettingsSheet = false
            }
        )
    }

    val uninstallTarget = selectedPlugin
    if (showUninstallConfirm && uninstallTarget != null) {
        AlertDialog(
            onDismissRequest = { showUninstallConfirm = false },
            title = { Text(stringResource(R.string.plugins_uninstall_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.plugins_uninstall_confirm_message,
                        uninstallTarget.displayName(languageTag)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.uninstall(uninstallTarget.id)
                    showUninstallConfirm = false
                    page = PluginHostPage.List
                }) {
                    Text(stringResource(R.string.plugins_uninstall))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUninstallConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun PluginHostToolbar(
    title: String,
    onBack: (() -> Unit)?,
    onSettings: (() -> Unit)? = null,
    onWorkflows: (() -> Unit)? = null,
    onImport: (() -> Unit)? = null,
    onUninstall: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    FeatherIcons.ChevronLeft,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        if (onSettings != null) {
            IconButton(onClick = onSettings) {
                Icon(
                    FeatherIcons.Settings,
                    contentDescription = stringResource(R.string.plugins_settings_title),
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        if (onWorkflows != null) {
            IconButton(onClick = onWorkflows) {
                Icon(
                    FeatherIcons.GitBranch,
                    contentDescription = stringResource(R.string.workflow_editor_list_title),
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        if (onImport != null) {
            IconButton(onClick = onImport) {
                Icon(
                    FeatherIcons.Plus,
                    contentDescription = stringResource(R.string.plugins_import),
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        if (onUninstall != null) {
            IconButton(onClick = onUninstall) {
                Icon(
                    FeatherIcons.Trash2,
                    contentDescription = stringResource(R.string.plugins_uninstall),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * 把导出的提交包交给系统分享。
 *
 * 没有索引仓库写权限的用户只能走到这里：把包发给维护者审核，
 * 好过把「没有权限」当成结论糊在屏幕上。
 */
private fun shareSubmission(context: android.content.Context, file: java.io.File) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, file.name)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            android.content.Intent.createChooser(intent, file.name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
