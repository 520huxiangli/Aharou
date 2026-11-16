package com.aharou.feature.plugin.presentation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.feature.plugin.presentation.drawer.AllToolsScreen
import com.aharou.feature.plugin.presentation.drawer.ProjectToolsDrawer
import com.aharou.feature.plugin.presentation.drawer.ProjectToolsViewModel
import com.aharou.feature.plugin.presentation.drawer.ToolDetailScreen
import com.aharou.feature.plugin.presentation.drawer.ToolUnavailableScreen

/** 工具箱内部页面：项目工具列表 → 全部工具 → 单个工具详情。 */
private const val PAGE_PROJECT = 0
private const val PAGE_ALL = 1
private const val PAGE_DETAIL = 2

/**
 * 项目工具箱：插件声明的工作区工具在这里加进当前项目并运行。
 *
 * 独立成覆盖层而不是常驻侧栏：工具是低频操作，占着侧栏反而挤掉会话与文件树。
 */
@Composable
internal fun PluginToolsHost(host: PluginHostState, onClose: () -> Unit) {
    if (!host.toolboxVisible) return
    val viewModel: ProjectToolsViewModel = hiltViewModel()
    val languageTag = host.languageTag
    val projectTools by viewModel.projectTools.collectAsStateWithLifecycle()
    val allTools by viewModel.allPluginTools.collectAsStateWithLifecycle()
    val selected by viewModel.selectedTool.collectAsStateWithLifecycle()
    var page by remember { mutableIntStateOf(PAGE_PROJECT) }
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    LaunchedEffect(host.toolboxVisible) {
        if (!host.toolboxVisible) page = PAGE_PROJECT
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            when (page) {
                PAGE_ALL -> AllToolsScreen(
                    availableTools = allTools,
                    enabledToolKeys = projectTools.map { it.toolKey }.toSet(),
                    languageTag = languageTag,
                    onBack = { page = PAGE_PROJECT },
                    onAddTool = { viewModel.addToolToProject(it, languageTag) },
                    onRemoveTool = { viewModel.removeToolFromProject(it.key) },
                    onViewDetail = {
                        viewModel.selectTool(it)
                        page = PAGE_DETAIL
                    }
                )

                PAGE_DETAIL -> selected?.let { tool ->
                    ToolDetailScreen(
                        tool = tool,
                        bridgeFactory = { pluginPage, post, onClosePage ->
                            host.viewModel.createWebBridge(
                                page = pluginPage,
                                pluginVersion = pluginPage.pluginVersion,
                                languageTag = languageTag,
                                theme = { if (isDark) "dark" else "light" },
                                onClosePage = onClosePage,
                                post = post
                            )
                        },
                        languageTag = languageTag,
                        onBack = { page = PAGE_PROJECT }
                    )
                } ?: ToolUnavailableScreen(onBack = { page = PAGE_PROJECT })

                else -> ProjectToolsDrawer(
                    workspacePath = viewModel.currentWorkspacePath,
                    tools = projectTools,
                    pluginToolsMap = allTools.associateBy { it.key },
                    languageTag = languageTag,
                    onOpenToolDetail = { tool, _ ->
                        viewModel.selectTool(tool)
                        page = PAGE_DETAIL
                    },
                    onOpenAllTools = { page = PAGE_ALL }
                )
            }
        }
    }
}
