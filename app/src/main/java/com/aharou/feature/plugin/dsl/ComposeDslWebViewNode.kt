/*
 * 移植自 AAswordman/Operit（原文件 ui/common/composedsl/ToolPkgComposeDslWebView.kt，LGPL-3.0）。
 * 本文件随 Aharou 以 GPL-3.0 分发。
 *
 * 改动：
 * - 不搬 Operit 的 JsEngine / JsJavaBridgeDelegates / 宿主控制器注册表那一整套内嵌 JS 运行时；
 *   插件侧的命令面收敛成「props 驱动」：`script` / `scripts` 变更时由宿主在已加载页面上
 *   evaluateJavascript，页面回话经 `onMessage` 这一个 action 回到插件。DSL 的每次交互都靠重新
 *   下发整棵树完成，页面的写操作同样走这条路，不需要第二套命令通道。
 * - 只允许 https 与插件包内相对路径；http / file / content / javascript / data 等一律拦下。
 *   插件包内文件由本文件的 `shouldInterceptRequest` 从插件目录读，WebView 自身不开文件访问。
 */

package com.aharou.feature.plugin.dsl

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.aharou.R
import com.aharou.core.util.FileLogger
import java.io.ByteArrayInputStream
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** 插件包内页面统一挂在这个假域名下，供相对资源请求与同源判断使用。 */
private const val PLUGIN_ORIGIN_HOST = "plugin.aharou.local"
private const val PLUGIN_ORIGIN = "https://$PLUGIN_ORIGIN_HOST/"

/** 页面回话的入参上限：一条消息超过这个体积多半是页面把整个 DOM 序列化出来了。 */
private const val MAX_PAGE_MESSAGE_CHARS = 256 * 1024

/** 面板网页里注入的宿主接口名；页面通过它把消息回给插件。 */
private const val WEB_MESSAGE_BRIDGE_NAME = "__AharouComposeWebViewHost"

private const val WEB_VIEW_TAG = "ComposeDslWebView"

/** 解析后的加载目标；无法加载时带一条给用户看的拒绝原因。 */
internal sealed interface ComposeDslWebTarget {
    val signature: String

    data class Remote(val url: String) : ComposeDslWebTarget {
        override val signature: String get() = "remote:$url"
    }

    data class InlineHtml(val html: String, val baseUrl: String?) : ComposeDslWebTarget {
        override val signature: String get() = "html:${baseUrl.orEmpty()}:${html.hashCode()}"
    }

    data class Blocked(val reason: String) : ComposeDslWebTarget {
        override val signature: String get() = "blocked:$reason"
    }
}

@Composable
internal fun renderWebViewNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val modifier = applyScopedCommonModifier(Modifier, props, modifierResolver)
    val host = LocalComposeDslWebViewHost.current
    val nodeKey = props["key"]?.toString()?.trim()?.ifBlank { null } ?: nodePath
    val rawUrl = props.stringOrNull("url")
    val rawHtml = props.stringOrNull("html")
    val target = remember(rawUrl, rawHtml, host?.pluginBaseDir) {
        resolveWebTarget(rawUrl = rawUrl, rawHtml = rawHtml, host = host)
    }
    when (target) {
        is ComposeDslWebTarget.Blocked -> ComposeDslWebBlockedNotice(
            reason = target.reason,
            modifier = modifier
        )
        else -> ComposeDslWebViewBlock(
            target = target,
            props = props,
            nodeKey = nodeKey,
            onAction = onAction,
            modifier = modifier
        )
    }
}

@Composable
private fun ComposeDslWebBlockedNotice(reason: String, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = stringResource(R.string.plugin_web_page_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            text = reason,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ComposeDslWebViewBlock(
    target: ComposeDslWebTarget,
    props: Map<String, Any?>,
    nodeKey: String,
    onAction: (String, Any?) -> Unit,
    modifier: Modifier
) {
    val context = LocalContext.current
    // 插件没写 height 时按整页处理：面板本身就是一整页 HTML，
    // 固定 320dp 会让内容只显示上半截，下面留一大片空白。
    val explicitHeight = props.floatOrNull("height")?.dp
    val allowScripts = props.bool("allowScripts", true)
    val onMessageActionId = ComposeDslParser.extractActionId(props["onMessage"])
    val currentOnAction by rememberUpdatedState(onAction)
    val currentMessageActionId by rememberUpdatedState(onMessageActionId)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf<String?>(null) }
    val loadedSignature = remember { mutableStateOf<String?>(null) }
    val pluginBaseDir = LocalComposeDslWebViewHost.current?.pluginBaseDir
    val assetServer = remember(pluginBaseDir) {
        pluginBaseDir?.let { ComposeDslPluginAssetServer(it) }
    }
    val bridge = remember { ComposeDslWebMessageBridge() }
    val webHost = LocalComposeDslWebViewHost.current
    // WebView 只创建一次，宿主 lambda 会随重组换新；用 State 让通道始终读最新那个
    val hostState = rememberUpdatedState(webHost)
    // 契约里 WebView 有一整套生命周期/资源回调，缺一个插件就有一块功能不响应
    val webCallbacks = remember(nodeKey, props) {
        mapOf(
            "onInterceptRequest" to ComposeDslParser.extractActionId(props["onInterceptRequest"]),
            "onShouldOverrideUrlLoading" to ComposeDslParser.extractActionId(props["onShouldOverrideUrlLoading"]),
            "onPageStarted" to ComposeDslParser.extractActionId(props["onPageStarted"]),
            "onPageFinished" to ComposeDslParser.extractActionId(props["onPageFinished"]),
            "onReceivedError" to ComposeDslParser.extractActionId(props["onReceivedError"]),
            "onReceivedHttpError" to ComposeDslParser.extractActionId(props["onReceivedHttpError"]),
            "onReceivedSslError" to ComposeDslParser.extractActionId(props["onReceivedSslError"]),
            "onConsoleMessage" to ComposeDslParser.extractActionId(props["onConsoleMessage"]),
            "onUrlChanged" to ComposeDslParser.extractActionId(props["onUrlChanged"]),
            "onProgressChanged" to ComposeDslParser.extractActionId(props["onProgressChanged"]),
            "onDownloadStart" to ComposeDslParser.extractActionId(props["onDownloadStart"]),
            "onStateChanged" to ComposeDslParser.extractActionId(props["onStateChanged"]),
            "onLifecycleEvent" to ComposeDslParser.extractActionId(props["onLifecycleEvent"])
        ).filterValues { !it.isNullOrBlank() }
    }
    val callbacksState = rememberUpdatedState(webCallbacks)

    /** 通知型回调：只派发，不等结果。 */
    fun dispatchWebEvent(actionId: String?, payload: Map<String, Any?>) {
        if (actionId.isNullOrBlank()) return
        runCatching { currentOnAction(actionId, payload) }
    }

    /**
     * 决策型回调：阻塞等插件给结论。
     *
     * WebViewClient 的这几个方法要求同步返回，而插件在另一个进程里；
     * 超时或没有通道时返回 null，调用方按「不处理」放行，绝不因为插件慢而卡死页面。
     */
    fun dispatchWebDecision(actionId: String?, payload: Map<String, Any?>): Any? {
        if (actionId.isNullOrBlank()) return null
        val dispatcher = hostState.value?.dispatchAwait ?: return null
        return runBlocking {
            withTimeoutOrNull(WEB_DECISION_TIMEOUT_MS) { dispatcher(actionId, payload) }
        }
    }

    /** 插件返回 `{action:"respond", response:{...}}` 时构造 WebView 响应；其它一律不处理。 */
    fun buildInterceptResponse(result: Map<*, *>): android.webkit.WebResourceResponse? {
        if (result["action"]?.toString()?.lowercase() != "respond") return null
        val response = result["response"] as? Map<*, *> ?: return null
        val mime = response["mimeType"]?.toString()?.ifBlank { "text/html" } ?: "text/html"
        val encoding = response["encoding"]?.toString()?.ifBlank { "utf-8" } ?: "utf-8"
        val status = (response["statusCode"] as? Number)?.toInt() ?: 200
        val reason = response["reasonPhrase"]?.toString()?.ifBlank { "OK" } ?: "OK"
        val headers = (response["headers"] as? Map<*, *>)
            ?.entries?.associate { it.key?.toString().orEmpty() to it.value?.toString().orEmpty() }
            .orEmpty()
        val bytes = when {
            response["filePath"] != null ->
                runCatching { java.io.File(response["filePath"].toString()).readBytes() }.getOrNull()
            response["text"] != null -> response["text"].toString().toByteArray(Charsets.UTF_8)
            response["bodyBase64"] != null ->
                runCatching { android.util.Base64.decode(response["bodyBase64"].toString(), android.util.Base64.DEFAULT) }.getOrNull()
            else -> null
        } ?: return null
        return android.webkit.WebResourceResponse(mime, encoding, status, reason, headers, bytes.inputStream())
    }

    // 插件注册的 WebView 控制器在节点上只剩 id 与方法名清单；函数本体留在插件进程里
    val controllerSpec = remember(nodeKey, props) { parseWebControllerSpec(props["controller"]) }

    // 页面线程（JavaBridge）回调里读不到最新的重组参数，统一在每次组合后刷新一次。
    SideEffect {
        bridge.onPayload = { raw ->
            handlePageMessage(
                raw = raw,
                nodeKey = nodeKey,
                pageUrl = webViewRef.value?.url,
                fallbackActionId = currentMessageActionId,
                onAction = currentOnAction,
                mainHandler = mainHandler
            )
        }
    }

    val webView = remember(context) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = allowScripts
                domStorageEnabled = true
                // 插件页面按需从插件目录取资源，由 shouldInterceptRequest 自己读文件；
                // WebView 的文件/内容访问一律关掉，网页里的 file:// 拿不到宿主任何目录。
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportZoom(true)
                builtInZoomControls = false
                displayZoomControls = false
                mediaPlaybackRequiresUserGesture = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            addJavascriptInterface(bridge, WEB_MESSAGE_BRIDGE_NAME)
            setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
                dispatchWebEvent(
                    callbacksState.value["onDownloadStart"],
                    mapOf(
                        "url" to (url ?: ""),
                        "mimeType" to (mimetype ?: ""),
                        "contentDisposition" to (contentDisposition ?: ""),
                        "contentLength" to contentLength
                    )
                )
            }
            if (controllerSpec != null) {
                addJavascriptInterface(
                    JsBridgeChannel(
                        controllerSpec = controllerSpec,
                        bridgeProvider = { hostState.value?.jsBridge },
                        webViewProvider = { webViewRef.value },
                        mainHandler = mainHandler
                    ),
                    JS_BRIDGE_NAME
                )
            }
            webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                    dispatchWebEvent(
                        callbacksState.value["onConsoleMessage"],
                        mapOf(
                            "message" to (message?.message() ?: ""),
                            "level" to (message?.messageLevel()?.name?.lowercase() ?: "log"),
                            "sourceId" to (message?.sourceId() ?: ""),
                            "lineNumber" to (message?.lineNumber() ?: 0)
                        )
                    )
                    return true
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    dispatchWebEvent(
                        callbacksState.value["onProgressChanged"],
                        mapOf("progress" to newProgress)
                    )
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    pageReady = false
                    pageError = null
                    dispatchWebEvent(callbacksState.value["onPageStarted"], mapOf("url" to (url ?: "")))
                    // 只拦主框架的协议，不拦子资源：子资源仍走 shouldInterceptRequest。
                    if (url != null && !isAllowedPageUrl(url)) {
                        view.stopLoading()
                        pageError = "blocked navigation: ${url.take(120)}"
                    }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    dispatchWebEvent(callbacksState.value["onPageFinished"], mapOf("url" to (url ?: "")))
                    if (pageError != null) return
                    pageReady = true
                    // 注入时机放在 onPageFinished：https 页面与插件包内页面都能拿到，
                    // 早于文档创建注入会被后到的导航冲掉。
                    view.evaluateJavascript(PAGE_BRIDGE_RUNTIME_SCRIPT, null)
                    if (controllerSpec != null) {
                        view.evaluateJavascript(jsBridgeBootstrapScript(controllerSpec), null)
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    dispatchWebEvent(
                        callbacksState.value["onReceivedError"],
                        mapOf(
                            "url" to (request?.url?.toString() ?: ""),
                            "reason" to (error?.description?.toString() ?: ""),
                            "isForMainFrame" to (request?.isForMainFrame == true)
                        )
                    )
                    if (request?.isForMainFrame == true) {
                        pageError = error?.description?.toString()?.take(160) ?: "load error"
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest?,
                    response: android.webkit.WebResourceResponse?
                ) {
                    dispatchWebEvent(
                        callbacksState.value["onReceivedHttpError"],
                        mapOf(
                            "url" to (request?.url?.toString() ?: ""),
                            "statusCode" to (response?.statusCode ?: 0),
                            "reasonPhrase" to (response?.reasonPhrase ?: "")
                        )
                    )
                }

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(
                    view: WebView,
                    handler: android.webkit.SslErrorHandler?,
                    error: android.net.http.SslError?
                ) {
                    dispatchWebEvent(
                        callbacksState.value["onReceivedSslError"],
                        mapOf("url" to (error?.url ?: ""), "error" to (error?.primaryError ?: 0))
                    )
                    // 插件不接管时按系统默认拒绝，绝不自动放行
                    handler?.cancel()
                }

                override fun onLoadResource(view: WebView, url: String?) {
                    dispatchWebEvent(callbacksState.value["onUrlChanged"], mapOf("url" to (url ?: "")))
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val url = request.url?.toString().orEmpty()
                    if (isAllowedPageUrl(url)) return false
                    // 协议不允许的导航：先问插件，它不接管就拦下
                    val decision = dispatchWebDecision(
                        callbacksState.value["onShouldOverrideUrlLoading"],
                        mapOf("url" to url)
                    ) as? Map<*, *>
                    return (decision?.get("action")?.toString() ?: "cancel") != "allow"
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): android.webkit.WebResourceResponse? {
                    val url = request.url?.toString() ?: return null
                    // 插件接管优先：它可以把请求映射到自己导出的文件
                    val actionId = callbacksState.value["onInterceptRequest"]
                    if (!actionId.isNullOrBlank()) {
                        val result = dispatchWebDecision(
                            actionId,
                            mapOf(
                                "url" to url,
                                "method" to request.method,
                                "isForMainFrame" to request.isForMainFrame
                            )
                        ) as? Map<*, *>
                        if (result != null) return buildInterceptResponse(result)
                    }
                    // 没接管时退回插件包内资源服务器
                    if (!url.startsWith(PLUGIN_ORIGIN, ignoreCase = true)) return null
                    return assetServer?.respond(url)
                }

            }
        }.also { webViewRef.value = it }
    }

    // 脚本开关随 props 变化；关掉后页面里的脚本立即失效。
    SideEffect {
        if (webView.settings.javaScriptEnabled != allowScripts) {
            webView.settings.javaScriptEnabled = allowScripts
        }
    }

    DisposableEffect(webView) {
        onDispose {
            pageReady = false
            runCatching { webView.removeJavascriptInterface(WEB_MESSAGE_BRIDGE_NAME) }
            webView.stopLoading()
            runCatching { webView.loadUrl("about:blank") }
            webView.destroy()
            webViewRef.value = null
        }
    }

    // 外层同样要撑满：Column 默认 wrap content，里面的 fillMaxSize 会拿不到高度约束
    val containerModifier = if (explicitHeight != null) modifier.fillMaxWidth() else modifier.fillMaxSize()
    Column(modifier = containerModifier) {
        AndroidView(
            factory = { webView },
            modifier = if (explicitHeight != null) {
                Modifier.fillMaxWidth().height(explicitHeight)
            } else {
                Modifier.fillMaxSize()
            },
            update = { view ->
                if (loadedSignature.value == target.signature) return@AndroidView
                loadedSignature.value = target.signature
                pageError = null
                when (target) {
                    is ComposeDslWebTarget.Remote -> view.loadUrl(target.url)
                    is ComposeDslWebTarget.InlineHtml ->
                        view.loadDataWithBaseURL(
                            target.baseUrl,
                            target.html,
                            "text/html",
                            "utf-8",
                            null
                        )
                    is ComposeDslWebTarget.Blocked -> Unit
                }
            }
        )

        val scriptToken = webScriptToken(props)
        LaunchedEffect(scriptToken, pageReady, allowScripts) {
            val script = webScriptText(props)
            if (script == null || scriptToken == null) return@LaunchedEffect
            if (!pageReady || !allowScripts) return@LaunchedEffect
            if (loadedSignature.value == null) return@LaunchedEffect
            runCatching { webView.evaluateJavascript(script, null) }
                .onFailure { error ->
                    FileLogger.w(WEB_VIEW_TAG, "面板网页执行脚本失败: $nodeKey", error)
                }
        }

        pageError?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * 把 props 里的加载目标归一化。
 *
 * 允许：https、插件包内相对路径（需要宿主给插件目录）、html 字符串。
 * 拒绝：http（明文）、file / content / javascript / data 等其它协议、协议相对 `//`、以 `/` 开头的
 * 绝对路径。拒绝理由原样回显，插件作者能直接看出是哪条规则拦的。
 */
internal fun resolveWebTarget(
    rawUrl: String?,
    rawHtml: String?,
    host: ComposeDslWebViewHost?
): ComposeDslWebTarget {
    val url = rawUrl?.trim().orEmpty()
    if (url.isNotEmpty()) {
        val lower = url.lowercase()
        return when {
            lower.startsWith("https://") -> ComposeDslWebTarget.Remote(url)
            lower.startsWith("http://") -> ComposeDslWebTarget.Blocked("http is not allowed, use https")
            lower.startsWith("//") -> ComposeDslWebTarget.Blocked("protocol-relative url is not allowed")
            url.startsWith("/") -> ComposeDslWebTarget.Blocked("absolute path is not allowed")
            url.indexOf(':') > 0 && SCHEME_PATTERN.matches(url.substring(0, url.indexOf(':') + 1)) ->
                ComposeDslWebTarget.Blocked(
                    "scheme is not allowed: ${url.substringBefore(':').lowercase()}"
                )
            else -> {
                val baseDir = host?.pluginBaseDir
                val relativePath = normalizeRelativePath(url)
                when {
                    baseDir == null -> ComposeDslWebTarget.Blocked(
                        "relative url needs the plugin package directory: $url"
                    )
                    relativePath == null -> ComposeDslWebTarget.Blocked(
                        "invalid relative url: $url"
                    )
                    else -> {
                        val entry = ComposeDslPluginAssetServer(baseDir).readText(relativePath)
                        if (entry == null) {
                            ComposeDslWebTarget.Blocked("plugin asset not found: $url")
                        } else {
                            val dir = relativePath.substringBeforeLast('/', "")
                            ComposeDslWebTarget.InlineHtml(
                                html = entry,
                                baseUrl = PLUGIN_ORIGIN + if (dir.isBlank()) "" else "$dir/"
                            )
                        }
                    }
                }
            }
        }
    }
    val html = rawHtml?.takeIf { it.isNotBlank() }
        ?: return ComposeDslWebTarget.Blocked("url or html is required")
    return ComposeDslWebTarget.InlineHtml(
        html = html,
        baseUrl = host?.pluginBaseDir?.let { PLUGIN_ORIGIN }
    )
}

private val SCHEME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.-]*:$")

private fun normalizeRelativePath(raw: String): String? {
    val path = raw.replace('\\', '/').removePrefix("./").trimStart('/')
    if (path.isBlank()) return null
    if (path.split('/').any { it == ".." }) return null
    return path
}

private fun isAllowedPageUrl(url: String): Boolean {
    if (url.isBlank() || url == "about:blank") return true
    val lower = url.lowercase()
    return lower.startsWith("https://") || lower.startsWith(PLUGIN_ORIGIN)
}

/** 脚本去重键：显式 `scriptId` 优先，否则用脚本内容本身，内容变了才重新执行。 */
private fun webScriptText(props: Map<String, Any?>): String? {
    props.stringOrNull("script")?.let { return it }
    val scripts = props["scripts"]
    val list = when (scripts) {
        is List<*> -> scripts.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
        is JSONArray -> (0 until scripts.length()).mapNotNull {
            scripts.optString(it).takeIf(String::isNotBlank)
        }
        else -> emptyList()
    }
    return list.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

private fun webScriptToken(props: Map<String, Any?>): String? {
    val script = webScriptText(props) ?: return null
    val explicit = props.stringOrNull("scriptId")
        ?: props.stringOrNull("evalId")
    return explicit?.let { "id:$it" } ?: "text:${script.hashCode()}"
}

/** 页面回话统一成一个 action：形态与 `ui.dispatch` 一致（actionId + payload）。 */
private fun handlePageMessage(
    raw: String,
    nodeKey: String,
    pageUrl: String?,
    fallbackActionId: String?,
    onAction: (String, Any?) -> Unit,
    mainHandler: Handler
) {
    if (raw.length > MAX_PAGE_MESSAGE_CHARS) {
        FileLogger.w(WEB_VIEW_TAG, "面板网页消息过大，已丢弃: $nodeKey (${raw.length})")
        return
    }
    val parsed = runCatching { jsonToValue(JSONTokener(raw).nextValue()) }.getOrNull()
    val envelopeActionId = (parsed as? Map<*, *>)?.get("actionId")?.toString()?.trim()?.ifBlank { null }
    val actionId = envelopeActionId ?: fallbackActionId
    if (actionId.isNullOrBlank()) {
        FileLogger.w(WEB_VIEW_TAG, "面板网页回话但没有 onMessage 动作，已丢弃: $nodeKey")
        return
    }
    val payload = mapOf(
        "nodeKey" to nodeKey,
        "type" to "webview.message",
        "payload" to if (parsed is Map<*, *> && parsed.containsKey("payload")) parsed["payload"] else parsed,
        "url" to pageUrl,
        "origin" to originOf(pageUrl),
        "ts" to System.currentTimeMillis()
    )
    mainHandler.post { onAction(actionId, payload) }
}

private fun originOf(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    val scheme = uri.scheme?.takeIf { it.isNotBlank() } ?: return null
    val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
    return "$scheme://$host"
}

/**
 * 页面侧的回话接口；`@JavascriptInterface` 方法在 WebView 的 JavaBridge 线程执行。
 *
 * 类不能是 Kotlin `private`：JavaBridge 在另一个包里反射调用，非 public 的类会
 * IllegalAccessException。`internal` 在字节码里就是 public。
 */

/** 插件在节点上留下的控制器描述：id + 每个 JS 接口的方法名。 */
internal data class ComposeDslWebControllerSpec(
    val id: String,
    val interfaces: Map<String, List<String>>
)

internal fun parseWebControllerSpec(raw: Any?): ComposeDslWebControllerSpec? {
    val obj = raw as? Map<*, *> ?: return null
    val box = obj["__aharouWebController"] as? Map<*, *> ?: return null
    val id = box["id"]?.toString()?.trim().orEmpty()
    if (id.isEmpty()) return null
    val interfaces = LinkedHashMap<String, List<String>>()
    (box["interfaces"] as? Map<*, *>)?.forEach { (key, value) ->
        val name = key?.toString()?.trim().orEmpty()
        if (name.isEmpty()) return@forEach
        val methods = (value as? List<*>)?.mapNotNull { it?.toString()?.trim()?.ifBlank { null } }.orEmpty()
        if (methods.isNotEmpty()) interfaces[name] = methods
    }
    return ComposeDslWebControllerSpec(id, interfaces)
}

/**
 * 网页里的 JS 接口调用通道。
 *
 * WebView 的 @JavascriptInterface 只能同步返回，而插件函数在另一个进程里，
 * 所以这里立即返回一个占位值，等插件算完再用 evaluateJavascript 把结果回填给页面的 Promise。
 */
private class JsBridgeChannel(
    private val controllerSpec: ComposeDslWebControllerSpec,
    private val bridgeProvider: () -> ComposeDslJsBridge?,
    private val webViewProvider: () -> WebView?,
    private val mainHandler: Handler
) {
    @JavascriptInterface
    fun call(interfaceName: String?, method: String?, token: String?, argsJson: String?) {
        val target = bridgeProvider()
        val webView = webViewProvider()
        val resolvedToken = token.orEmpty()
        if (target == null || webView == null || resolvedToken.isEmpty()) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            val settle = runCatching {
                target.call(
                    controllerSpec.id,
                    interfaceName.orEmpty(),
                    method.orEmpty(),
                    argsJson.orEmpty().ifBlank { "[]" }
                )
            }
            val payload = settle.fold(
                onSuccess = { result -> "true," + JSONObject.quote(result) },
                onFailure = { error ->
                    "false," + JSONObject.quote(error.message ?: error.javaClass.simpleName)
                }
            )
            mainHandler.post {
                webView.evaluateJavascript(
                    "window.__aharouJsSettle && window.__aharouJsSettle(${JSONObject.quote(resolvedToken)}, $payload);",
                    null
                )
            }
        }
    }
}

/** 给页面装上 JS 接口垫片：把 await Guardian.xxx() 变成一次跨进程调用。 */
private fun jsBridgeBootstrapScript(spec: ComposeDslWebControllerSpec): String {
    val interfaces = spec.interfaces.entries.joinToString(",") { (name, methods) ->
        val list = methods.joinToString(",") { JSONObject.quote(it) }
        JSONObject.quote(name) + ":[" + list + "]"
    }
    return """
        (function () {
          var channel = window['$JS_BRIDGE_NAME'];
          if (!channel || typeof channel.call !== 'function') { return; }
          var pending = {};
          var seq = 0;
          window.__aharouJsSettle = function (token, ok, value) {
            var entry = pending[token];
            if (!entry) { return; }
            delete pending[token];
            if (ok) { entry.resolve(value); } else { entry.reject(new Error(String(value))); }
          };
          var spec = { $interfaces };
          Object.keys(spec).forEach(function (ifaceName) {
            var target = {};
            spec[ifaceName].forEach(function (method) {
              target[method] = function () {
                var args = Array.prototype.slice.call(arguments);
                var json;
                try { json = JSON.stringify(args); } catch (error) { json = '[]'; }
                return new Promise(function (resolve, reject) {
                  var token = 'js' + (seq += 1);
                  pending[token] = { resolve: resolve, reject: reject };
                  channel.call(ifaceName, method, token, json);
                });
              };
            });
            window[ifaceName] = target;
          });
        })();
    """.trimIndent()
}

internal class ComposeDslWebMessageBridge {
    var onPayload: (String) -> Unit = {}

    @JavascriptInterface
    fun postMessage(payload: String?) {
        val raw = payload.orEmpty()
        if (raw.isBlank()) return
        runCatching { onPayload(raw) }
    }
}

/**
 * 插件包内文件的只读服务：把 `https://plugin.aharou.local/<相对路径>` 映射到插件目录，
 * 任何解析后落到目录外（`..`、符号链接）的请求一律 404。
 */
internal class ComposeDslPluginAssetServer(baseDir: File) {

    private val root: File? = runCatching { baseDir.canonicalFile }.getOrNull()

    fun readText(relativePath: String): String? {
        val bytes = readBytes(relativePath) ?: return null
        return runCatching { bytes.toString(Charsets.UTF_8) }.getOrNull()
    }

    fun readBytes(relativePath: String): ByteArray? {
        val base = root ?: return null
        if (!base.isDirectory) return null
        val normalized = normalizeRelativePath(relativePath) ?: return null
        val file = runCatching { File(base, normalized).canonicalFile }.getOrNull() ?: return null
        if (!file.path.startsWith(base.path + File.separator)) return null
        if (!file.isFile) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    fun respond(url: String): WebResourceResponse? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (uri.host != PLUGIN_ORIGIN_HOST) return null
        val path = uri.path.orEmpty().trimStart('/')
        val bytes = readBytes(path)
            ?: return WebResourceResponse(
                "text/plain",
                "utf-8",
                404,
                "Not Found",
                mapOf("Cache-Control" to "no-store"),
                ByteArrayInputStream(ByteArray(0))
            )
        return WebResourceResponse(
            mimeTypeOf(path),
            "utf-8",
            200,
            "OK",
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(bytes)
        )
    }

    private fun mimeTypeOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "application/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "txt", "md" -> "text/plain"
        else -> "application/octet-stream"
    }
}

/**
 * 注入到页面的宿主运行时代码。
 *
 * 页面回话的写法：`AharouComposeWebView.postMessage(x)`（或 `send`）、`__aharouComposeSend(x)`
 * 与 `__aharouPostMessage(x)`，三者等价。宿主推给页面的消息走 `window.__aharouOverlayMessage(payload)`，
 * 与浮层页面同一套命名；页面可以注册 `AharouComposeWebView.onMessage(handler)`、监听
 * `window` 的 `aharou:message` 事件，或按浏览器习惯监听 `message` 事件（`event.data` 是消息体）。
 */
private const val JS_BRIDGE_NAME = "AharouDslJsBridge"

/** 决策型回调的最长等待：插件算得慢也不能把页面卡住。 */
private const val WEB_DECISION_TIMEOUT_MS = 5_000L

private val PAGE_BRIDGE_RUNTIME_SCRIPT: String = """
    (function () {
      if (window.__aharouComposeWebViewInstalled) { return; }
      window.__aharouComposeWebViewInstalled = true;
      var bridge = window['$WEB_MESSAGE_BRIDGE_NAME'];
      var handlers = [];
      function stringify(value) {
        if (typeof value === 'string') { return value; }
        try { return JSON.stringify(value === undefined ? null : value); }
        catch (error) { return String(value); }
      }
      function send(value) {
        if (!bridge || typeof bridge.postMessage !== 'function') { return false; }
        bridge.postMessage(stringify(value));
        return true;
      }
      function dispatchInbound(value) {
        handlers.slice().forEach(function (handler) {
          try { handler(value); } catch (error) { console.error('aharou webview handler failed', error); }
        });
        try { window.dispatchEvent(new CustomEvent('aharou:message', { detail: value })); } catch (error) {}
        try {
          window.dispatchEvent(new MessageEvent('message', { data: value, origin: '', source: null }));
        } catch (error) {}
      }
      window.__aharouOverlayMessage = function (payload) {
        var value = payload;
        if (typeof payload === 'string') {
          try { value = JSON.parse(payload); } catch (error) { value = payload; }
        }
        dispatchInbound(value);
      };
      window.AharouComposeWebView = {
        postMessage: send,
        send: send,
        onMessage: function (handler) {
          if (typeof handler === 'function') { handlers.push(handler); }
          return handlers.length;
        },
        invokeAction: function (actionId, payload) {
          return send({ actionId: String(actionId), payload: payload === undefined ? null : payload });
        }
      };
      window.__aharouComposeSend = send;
      window.__aharouPostMessage = send;
    })();
""".trimIndent()

private fun jsonToValue(value: Any?): Any? = when (value) {
    null, JSONObject.NULL -> null
    is JSONObject -> value.keys().asSequence().associateWith { jsonToValue(value.opt(it)) }
    is JSONArray -> (0 until value.length()).map { jsonToValue(value.opt(it)) }
    else -> value
}
