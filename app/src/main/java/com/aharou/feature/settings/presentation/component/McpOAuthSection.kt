package com.aharou.feature.settings.presentation.component

import android.annotation.SuppressLint
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.SegmentedTabs
import com.aharou.feature.agent.domain.mcp.MCP_OAUTH_CUSTOM_SCHEME_REDIRECT
import com.aharou.feature.agent.domain.mcp.MCP_OAUTH_LOOPBACK_REDIRECT
import com.aharou.feature.agent.domain.mcp.McpManager
import com.aharou.feature.agent.domain.mcp.McpOAuthClient
import com.aharou.feature.agent.domain.mcp.McpOAuthErrorKind
import com.aharou.feature.agent.domain.mcp.McpOAuthFlowException
import com.aharou.feature.agent.domain.mcp.McpOAuthStatus
import com.aharou.feature.agent.domain.mcp.McpServerEntry
import com.aharou.feature.agent.domain.mcp.oauthStatusOf
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Info
import compose.icons.feathericons.Shield
import compose.icons.feathericons.X
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 授权面板所处阶段。 */
enum class McpOAuthPhase { FORM, WORKING, AUTHORIZING, DONE, ERROR }

/** 面板内需要展示的一次性提示（非错误）。 */
enum class McpOAuthFlowMessage { NEED_CLIENT_ID }

/** 授权面板一次性会话状态。 */
data class McpOAuthFlow(
    val entry: McpServerEntry,
    val phase: McpOAuthPhase,
    val authUrl: String? = null,
    val message: McpOAuthFlowMessage? = null,
    val error: McpOAuthErrorKind? = null,
    val errorDetail: String? = null,
    val redirectUri: String = MCP_OAUTH_CUSTOM_SCHEME_REDIRECT
)

/**
 * 授权面板的编排层：UI 单飞，持有当前会话状态并驱动 [McpOAuthClient]（发现 → 注册 → 授权 → 换令牌）。
 * 做成单例走 [McpOAuthEntryPoint] 注入，避免改动设置页大文件与 ViewModel。
 */
@Singleton
class McpOAuthController @Inject constructor(
    private val oauthClient: McpOAuthClient,
    private val mcpManager: McpManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _state = MutableStateFlow<McpOAuthFlow?>(null)
    val state: StateFlow<McpOAuthFlow?> = _state.asStateFlow()

    /** 打开面板（进入表单阶段）。 */
    fun open(entry: McpServerEntry) {
        job?.cancel()
        _state.value = McpOAuthFlow(
            entry = entry,
            phase = McpOAuthPhase.FORM,
            redirectUri = entry.server.oauth?.redirectUri?.takeIf { it.isNotBlank() }
                ?: MCP_OAUTH_CUSTOM_SCHEME_REDIRECT
        )
    }

    fun close() {
        job?.cancel()
        _state.value = null
    }

    /** 回到表单（错误页「重试」用）。 */
    fun backToForm() {
        _state.value = _state.value?.copy(
            phase = McpOAuthPhase.FORM,
            authUrl = null,
            message = null,
            error = null,
            errorDetail = null
        )
    }

    /** 表单「开始授权」：发现 + 注册（如需）+ 打开授权页。 */
    fun start(clientId: String, clientSecret: String, redirectUri: String) {
        val flow = _state.value ?: return
        val entry = flow.entry
        val serverUrl = entry.server.url
        if (serverUrl.isNullOrBlank()) {
            _state.value = flow.copy(
                phase = McpOAuthPhase.ERROR,
                error = McpOAuthErrorKind.DISCOVERY_FAILED,
                errorDetail = null
            )
            return
        }
        job?.cancel()
        job = scope.launch {
            _state.value = flow.copy(phase = McpOAuthPhase.WORKING, message = null, error = null, errorDetail = null)
            try {
                when (
                    val result = oauthClient.beginAuthorization(
                        serverName = entry.server.name,
                        scope = entry.scope,
                        serverUrl = serverUrl,
                        clientId = clientId,
                        clientSecret = clientSecret,
                        redirectUri = redirectUri
                    )
                ) {
                    is McpOAuthClient.BeginResult.NeedsClientId ->
                        _state.value = flow.copy(
                            phase = McpOAuthPhase.FORM,
                            redirectUri = redirectUri,
                            message = McpOAuthFlowMessage.NEED_CLIENT_ID
                        )
                    is McpOAuthClient.BeginResult.AuthUrl ->
                        _state.value = flow.copy(
                            phase = McpOAuthPhase.AUTHORIZING,
                            redirectUri = redirectUri,
                            authUrl = result.url
                        )
                }
            } catch (e: McpOAuthFlowException) {
                fail(flow, e)
            } catch (e: Exception) {
                fail(flow, McpOAuthFlowException(McpOAuthErrorKind.UNKNOWN, e.message, e))
            }
        }
    }

    /** WebView 拦截到回调地址：用 code 换令牌并落盘，随后重连该 server。 */
    fun onRedirect(url: String) {
        val flow = _state.value ?: return
        val entry = flow.entry
        job?.cancel()
        job = scope.launch {
            _state.value = flow.copy(phase = McpOAuthPhase.WORKING, authUrl = null, error = null, errorDetail = null)
            try {
                oauthClient.completeAuthorization(entry.server.name, url)
                mcpManager.reloadServer(entry.server.name)
                _state.value = _state.value?.copy(phase = McpOAuthPhase.DONE)
            } catch (e: McpOAuthFlowException) {
                fail(_state.value ?: flow, e)
            } catch (e: Exception) {
                fail(_state.value ?: flow, McpOAuthFlowException(McpOAuthErrorKind.UNKNOWN, e.message, e))
            }
        }
    }

    /** 授权页加载失败（WebView 回调）。 */
    fun onWebViewError(detail: String) {
        val flow = _state.value ?: return
        _state.value = flow.copy(
            phase = McpOAuthPhase.ERROR,
            error = McpOAuthErrorKind.NETWORK,
            errorDetail = detail
        )
    }

    /** 撤销授权：清空令牌并重连。 */
    fun revoke() {
        val flow = _state.value ?: return
        val entry = flow.entry
        job?.cancel()
        job = scope.launch {
            runCatching { oauthClient.revoke(entry.server.name, entry.scope) }
            runCatching { mcpManager.reloadServer(entry.server.name) }
            _state.value = flow.copy(
                phase = McpOAuthPhase.FORM,
                message = null,
                error = null,
                errorDetail = null,
                authUrl = null
            )
        }
    }

    private fun fail(flow: McpOAuthFlow, e: McpOAuthFlowException) {
        _state.value = flow.copy(phase = McpOAuthPhase.ERROR, error = e.kind, errorDetail = e.detail)
    }
}

/** Composable 取不到注入单例，走 EntryPoint（同 ArtifactPreview 的做法）。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface McpOAuthEntryPoint {
    fun mcpOAuthController(): McpOAuthController
}

/**
 * 授权面板宿主：挂在主窗口内的设置页（[McpSection]）里，**不进 ModalBottomSheet**。
 *
 * 老坑：WebView 挂进底栏弹层的独立窗口，部分 OEM GPU 合成失败会白屏（见 feature/browser 与
 * ArtifactPreview 的主窗口内联宿主注释）。这里用全屏 Surface 覆盖内容区，WebView 画在主窗口。
 */
@Composable
internal fun McpOAuthHost(controller: McpOAuthController) {
    val flow by controller.state.collectAsStateWithLifecycle()
    flow?.let { McpOAuthOverlay(flow = it, controller = controller) }
}

@Composable
private fun McpOAuthOverlay(flow: McpOAuthFlow, controller: McpOAuthController) {
    BackHandler { controller.close() }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.semanticColors.pageBackground) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { controller.close() }) {
                    Icon(FeatherIcons.X, contentDescription = stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    text = stringResource(R.string.mcp_oauth_title, flow.entry.server.name),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.semanticColors.subtleBorder)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (flow.phase) {
                    McpOAuthPhase.FORM -> McpOAuthForm(flow, controller)
                    McpOAuthPhase.WORKING -> McpOAuthWorking(flow)
                    McpOAuthPhase.AUTHORIZING -> {
                        val authUrl = flow.authUrl
                        if (authUrl.isNullOrBlank()) {
                            McpOAuthWorking(flow)
                        } else {
                            McpOAuthWebView(
                                url = authUrl,
                                redirectUri = flow.redirectUri,
                                onRedirect = { controller.onRedirect(it) },
                                onError = { controller.onWebViewError(it) }
                            )
                        }
                    }
                    McpOAuthPhase.DONE -> McpOAuthDone(controller)
                    McpOAuthPhase.ERROR -> McpOAuthErrorView(flow, controller)
                }
            }
        }
    }
}

@Composable
private fun McpOAuthForm(flow: McpOAuthFlow, controller: McpOAuthController) {
    val seedOAuth = flow.entry.server.oauth
    val isLoopback = flow.redirectUri.startsWith("http://127.0.0.1")
    var clientId by remember(flow.entry.server.name) { mutableStateOf(seedOAuth?.clientId.orEmpty()) }
    var clientSecret by remember(flow.entry.server.name) { mutableStateOf("") }
    var redirectIndex by remember(flow.entry.server.name, isLoopback) { mutableStateOf(if (isLoopback) 1 else 0) }
    val redirectUri = if (redirectIndex == 0) MCP_OAUTH_CUSTOM_SCHEME_REDIRECT else MCP_OAUTH_LOOPBACK_REDIRECT
    val status = oauthStatusOf(seedOAuth)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Shield,
                title = stringResource(R.string.mcp_oauth_status_label),
                subtitle = stringResource(oauthStatusLabelRes(status))
            )
        }

        AppTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = stringResource(R.string.mcp_oauth_client_id),
            placeholder = stringResource(R.string.mcp_oauth_client_id_hint),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        AppTextField(
            value = clientSecret,
            onValueChange = { clientSecret = it },
            label = stringResource(R.string.mcp_oauth_client_secret),
            placeholder = stringResource(R.string.mcp_oauth_client_secret_hint),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Text(
            text = stringResource(R.string.mcp_oauth_redirect_label),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SegmentedTabs(
            selected = redirectIndex,
            labels = listOf(
                stringResource(R.string.mcp_oauth_redirect_custom),
                stringResource(R.string.mcp_oauth_redirect_loopback)
            ),
            onSelect = { redirectIndex = it }
        )
        Text(
            text = stringResource(R.string.mcp_oauth_redirect_hint, redirectUri),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        )

        if (flow.message == McpOAuthFlowMessage.NEED_CLIENT_ID) {
            Text(
                text = stringResource(R.string.mcp_oauth_need_client_id),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        Button(
            onClick = { controller.start(clientId, clientSecret, redirectUri) },
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(Radius.mdLarge)
        ) {
            Text(
                text = stringResource(R.string.mcp_oauth_authorize),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        }

        if (status != McpOAuthStatus.NOT_AUTHORIZED) {
            TextButton(
                onClick = { controller.revoke() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.mcp_oauth_revoke),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun McpOAuthWorking(flow: McpOAuthFlow) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = stringResource(
                if (flow.authUrl == null) R.string.mcp_oauth_working_discovering else R.string.mcp_oauth_working_exchanging
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun McpOAuthDone(controller: McpOAuthController) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            FeatherIcons.Check,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.tertiary
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = stringResource(R.string.mcp_oauth_success),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(Spacing.lg))
        Button(onClick = { controller.close() }) {
            Text(stringResource(R.string.mcp_oauth_done))
        }
    }
}

@Composable
private fun McpOAuthErrorView(flow: McpOAuthFlow, controller: McpOAuthController) {
    val base = stringResource(errorLabelRes(flow.error))
    val detail = flow.errorDetail
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            FeatherIcons.Info,
            contentDescription = null,
            modifier = Modifier.size(36.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = if (detail.isNullOrBlank()) base else "$base：$detail",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Button(onClick = { controller.backToForm() }) {
                Text(stringResource(R.string.mcp_oauth_retry))
            }
            TextButton(onClick = { controller.close() }) {
                Text(stringResource(R.string.common_close))
            }
        }
    }
}

/**
 * 授权页 WebView（主窗口内联）。安全收紧对照 [ArtifactWebView]：
 * - `allowFileAccess` / `allowContentAccess` 关；跨源文件读关；混合内容禁；
 * - 不调用 `addJavascriptInterface`，页面触达不到原生；
 * - 导航白名单：命中回调地址即交给上层取 code 并拦截；非 http(s) scheme 一律拦下（不外跳其它 App）；
 * - 外部 http(s) 链接留在内置 WebView 内，不跳系统浏览器。
 * JS 与 DOM Storage 保持开启：多数登录/同意页依赖它们，否则页面无法完成授权。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun McpOAuthWebView(
    url: String,
    redirectUri: String,
    onRedirect: (String) -> Unit,
    onError: (String) -> Unit
) {
    val context = LocalContext.current
    val webView = remember {
        WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(false)
                setGeolocationEnabled(false)
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url?.toString().orEmpty()
                    if (target.startsWith(redirectUri)) {
                        onRedirect(target)
                        return true
                    }
                    val scheme = request.url?.scheme?.lowercase()
                    // 只允许 http(s) 在页面内继续导航；file/content/自定义 scheme 一律拦下，不交给系统。
                    return scheme != "http" && scheme != "https"
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) onError(error.description?.toString().orEmpty())
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    onError("render process gone")
                    return true
                }
            }
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    LaunchedEffect(url) { webView.loadUrl(url) }

    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
}

private fun oauthStatusLabelRes(status: McpOAuthStatus): Int = when (status) {
    McpOAuthStatus.NOT_AUTHORIZED -> R.string.mcp_oauth_status_not_authorized
    McpOAuthStatus.AUTHORIZED -> R.string.mcp_oauth_status_authorized
    McpOAuthStatus.EXPIRED -> R.string.mcp_oauth_status_expired
}

private fun errorLabelRes(kind: McpOAuthErrorKind?): Int = when (kind) {
    McpOAuthErrorKind.DISCOVERY_FAILED -> R.string.mcp_oauth_error_discovery
    McpOAuthErrorKind.DCR_FAILED -> R.string.mcp_oauth_error_dcr
    McpOAuthErrorKind.NETWORK -> R.string.mcp_oauth_error_network
    McpOAuthErrorKind.TOKEN_EXCHANGE_FAILED -> R.string.mcp_oauth_error_token
    McpOAuthErrorKind.AUTH_DENIED -> R.string.mcp_oauth_error_denied
    McpOAuthErrorKind.INVALID_RESPONSE -> R.string.mcp_oauth_error_invalid
    McpOAuthErrorKind.UNKNOWN, null -> R.string.mcp_oauth_error_unknown
}
