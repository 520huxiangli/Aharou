package com.aharou.feature.plugin.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.util.FileLogger
import com.aharou.feature.plugin.dsl.ComposeDslJsBridge
import com.aharou.feature.plugin.dsl.ComposeDslRenderResult
import com.aharou.feature.plugin.dsl.ComposeDslWebViewHost
import com.aharou.feature.plugin.dsl.ComposeDslTextInputDispatchQueue
import com.aharou.feature.plugin.dsl.RenderComposeDsl
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 面板内部的三态：首帧加载、可渲染、失败。 */
private sealed interface DslPanelState {
    data object Loading : DslPanelState
    data class Ready(val routeId: String, val result: ComposeDslRenderResult) : DslPanelState
    data class Failed(val message: String) : DslPanelState
}

/**
 * Compose DSL 面板宿主：宿主只提供标题栏、加载态与失败态，页面内容全部由插件渲染层画。
 *
 * 关闭入口由宿主提供（插件没法保证自己的树里有关闭按钮），关闭回调与 HTML 页面宿主同一条。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PluginDslPanelScreen(
    page: PluginDslPage,
    languageTag: String,
    viewModel: PluginsViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bridge = remember(page.key) { viewModel.createDslBridge(page.pluginId) }
    DisposableEffect(bridge) {
        onDispose { bridge.close() }
    }

    var panelState by remember(page.key) { mutableStateOf<DslPanelState>(DslPanelState.Loading) }
    var retryToken by remember(page.key) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val dispatchMutex = remember(page.key) { Mutex() }
    // 输入框每敲一个字都会回传一次，必须串行派发，否则运行时收到的顺序与输入顺序不一致。
    val textQueue = remember(bridge) { ComposeDslTextInputDispatchQueue(onAllSettled = {}) }

    LaunchedEffect(page.key, retryToken) {
        if (panelState !is DslPanelState.Ready) panelState = DslPanelState.Loading
        panelState = try {
            val opened = bridge.open(page.localId, page.screen)
            DslPanelState.Ready(opened.routeId, opened.result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "插件面板渲染失败: ${page.pluginId}/${page.localId}", e)
            DslPanelState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    // 插件被停用或卸载后不要再留着一棵点不动的树；插件列表短暂变空（重载）时不跟着关。
    val plugins by viewModel.plugins.collectAsStateWithLifecycle()
    var pluginWasActive by remember(page.key) { mutableStateOf(false) }
    LaunchedEffect(plugins) {
        if (plugins.any { it.id == page.pluginId && it.isActive }) {
            pluginWasActive = true
        } else if (pluginWasActive) {
            onClose()
        }
    }

    suspend fun dispatchAction(actionId: String, value: Any?) {
        var failure: String? = null
        dispatchMutex.withLock {
            val current = panelState as? DslPanelState.Ready ?: return
            try {
                val next = bridge.dispatch(current.routeId, actionId, value)
                panelState = DslPanelState.Ready(current.routeId, next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 单次动作失败不清空已渲染的树：界面留在原地，错误走 snackbar。
                FileLogger.w(TAG, "插件面板动作失败: ${page.pluginId}/${page.localId} $actionId", e)
                failure = e.message ?: actionId
            }
        }
        // snackbar 会挂到用户关掉为止，不能占着派发锁，否则下一次点击全被拖住。
        failure?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = page.title.resolve(languageTag),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = FeatherIcons.ArrowLeft,
                            contentDescription = stringResource(R.string.common_back),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            when (val current = panelState) {
                DslPanelState.Loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }

                is DslPanelState.Failed -> DslPanelFailure(
                    message = current.message,
                    onRetry = { retryToken += 1 }
                )

                is DslPanelState.Ready -> RenderComposeDsl(
                    tree = current.result.tree,
                    state = current.result.state,
                    // 插件包内资源与网页 JS 接口都要靠宿主传下去：
                    // 不传时 WebView 读不到插件目录，接口调用也没有回插件的通道。
                    webViewHost = ComposeDslWebViewHost(
                        pluginBaseDir = viewModel.pluginInstallDir(page.pluginId),
                        jsBridge = ComposeDslJsBridge { controllerId, interfaceName, method, argsJson ->
                            bridge.callWebJs(
                                routeId = current.routeId,
                                controllerId = controllerId,
                                interfaceName = interfaceName,
                                method = method,
                                argsJson = argsJson
                            )
                        },
                        // WebView 的拦截/导航决策要同步拿插件结论，只能在这里阻塞等
                        dispatchAwait = { actionId, payload ->
                            bridge.dispatch(current.routeId, actionId, payload).actionResult
                        }
                    ),
                    onAction = { actionId, value ->
                        scope.launch { dispatchAction(actionId, value) }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onTextInput = { actionId, text ->
                        textQueue.enqueue(actionId, text) { entry, onSettled ->
                            scope.launch {
                                try {
                                    dispatchAction(entry.actionId, entry.text)
                                } finally {
                                    onSettled()
                                }
                            }
                        }
                    },
                    scrollStateKey = "plugin_dsl:${page.key}"
                )
            }
        }
    }
}

@Composable
private fun DslPanelFailure(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.plugin_web_page_failed),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        TextButton(onClick = onRetry) {
            Text(text = stringResource(R.string.plugin_web_page_retry))
        }
    }
}

private const val TAG = "PluginDslPanel"
