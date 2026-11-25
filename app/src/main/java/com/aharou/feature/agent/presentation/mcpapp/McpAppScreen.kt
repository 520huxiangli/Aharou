package com.aharou.feature.agent.presentation.mcpapp

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.agent.domain.mcp.McpAppGateway
import com.aharou.feature.agent.domain.mcp.McpCallResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject

/**
 * MCP App 的宿主界面：把 server 暴露的一段 HTML 当页面跑，页面里的交互经
 * `McpBridge.callTool` 回到同一条 server。
 *
 * 安全边界对应下面几行代码，改动时别放松：
 * - WebView 关掉 `allowFileAccess` / `allowContentAccess`，页面拿不到本地文件；
 * - `shouldOverrideUrlLoading` 一律返回 true，页面里点外链不会把宿主变成浏览器；
 * - 桥只暴露一个 `callTool(id, name, args)`，没有 eval、没有读文件、没有跨 server；
 * - 页面是外部内容，工具结果只回给页面自己，不写进聊天历史。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun McpAppScreen(
    gateway: McpAppGateway,
    server: String,
    entryUri: String,
    workspacePath: String?,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val currentGateway by rememberUpdatedState(gateway)

    LaunchedEffect(server, entryUri) {
        loading = true
        error = null
        runCatching { currentGateway.readEntry(server, entryUri, workspacePath) }
            .onSuccess { html ->
                val doc = injectShim(html)
                webView?.loadDataWithBaseURL(BASE_URL, doc, "text/html", "utf-8", null)
            }
            .onFailure { error = it.message }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = server,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Text(
                            text = stringResource(R.string.mcp_app_close),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            val failure = error
            when {
                failure != null -> Text(
                    text = stringResource(R.string.mcp_app_failed, failure),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(Spacing.lg),
                )

                else -> AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        @SuppressLint("SetJavaScriptEnabled")
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.blockNetworkLoads = false
                            webViewClient = object : WebViewClient() {
                                // 页面里的任何跳转都不放行：宿主不是浏览器，点了也没反应比开错页安全。
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean = true
                            }
                            addJavascriptInterface(
                                McpAppBridge(
                                    scope = scope,
                                    webViewProvider = { webView },
                                    onCall = { name, args ->
                                        currentGateway.callTool(server, name, args, workspacePath)
                                    },
                                ),
                                BRIDGE_NAME,
                            )
                            webView = this
                        }
                    },
                )
            }
            if (loading && error == null) {
                Text(
                    text = stringResource(R.string.mcp_app_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.lg),
                )
            }
        }
    }
}

/** 页面里可调用的桥。只暴露一个方法，参数全是字符串，避免把 Kotlin 对象直接甩给 JS。 */
private class McpAppBridge(
    private val scope: CoroutineScope,
    private val webViewProvider: () -> WebView?,
    private val onCall: suspend (String, JsonObject) -> McpCallResult,
) {

    @JavascriptInterface
    fun callTool(id: String, name: String, argsJson: String) {
        scope.launch {
            val arguments = runCatching {
                Json.parseToJsonElement(argsJson).jsonObject
            }.getOrElse { JsonObject(emptyMap()) }
            val outcome = runCatching { onCall(name, arguments) }
            val ok = outcome.getOrNull()?.let { !it.isError } == true
            val payload = outcome.fold(
                onSuccess = { it.text },
                onFailure = { it.message ?: "tool call failed" },
            )
            val script = "window.__mcpResolve(${JSONObject.quote(id)}, $ok, ${JSONObject.quote(payload)})"
            webViewProvider()?.let { view ->
                view.post { view.evaluateJavascript(script, null) }
            }
        }
    }
}

/**
 * 把宿主提供的 `window.mcp.callTool` 注入页面。
 *
 * 优先插在 `</head>` 前，让页面自己的脚本运行时桥已经就绪；页面没有 head 时退化成前面拼接，
 * 这种情况下页面首屏的脚本可能早于桥，属于页面写法问题，不在这里猜。
 */
private fun injectShim(html: String): String {
    val shim = "<script>$JS_SHIM</script>"
    val headEnd = html.indexOf("</head>", ignoreCase = true)
    return if (headEnd >= 0) {
        html.substring(0, headEnd) + shim + html.substring(headEnd)
    } else {
        shim + html
    }
}

private const val BASE_URL = "https://mcp-app.invalid/"
private const val BRIDGE_NAME = "McpBridge"

private val JS_SHIM = """
(function(){
  window.__pending = window.__pending || {};
  window.__seq = window.__seq || 0;
  window.mcp = {
    callTool: function(name, args){
      return new Promise(function(resolve, reject){
        var id = 'p' + (++window.__seq);
        window.__pending[id] = {resolve: resolve, reject: reject};
        McpBridge.callTool(id, String(name), JSON.stringify(args || {}));
      });
    }
  };
  window.__mcpResolve = function(id, ok, payload){
    var p = window.__pending[id];
    if(!p) return;
    delete window.__pending[id];
    if(ok) p.resolve(payload); else p.reject(new Error(payload));
  };
})();
""".trimIndent()
