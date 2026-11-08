package com.aharou.feature.settings.presentation.component

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.feature.browser.presentation.BrowserWebView
import java.io.ByteArrayInputStream

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NapCatWebUiSheet(
    url: String, tokens: List<String>, accountStatus: String, refreshEnabled: Boolean,
    onRefresh: () -> Unit, onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val origin = remember(url) { Uri.parse(url) }
    val webView = remember(url, tokens) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.saveFormData = false
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setDownloadListener { _, _, _, _, _ -> }
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            webViewClient = object : WebViewClient() {
                private fun sameOrigin(uri: Uri): Boolean =
                    uri.scheme == origin.scheme && uri.host == origin.host && uri.port == origin.port

                private fun allowed(uri: Uri): Boolean {
                    if (sameOrigin(uri)) return true
                    if (uri.scheme != "https" || uri.userInfo != null) return false
                    val secrets = (tokens + listOfNotNull(
                        origin.getQueryParameter("token"), origin.getQueryParameter("access_token"),
                        origin.getQueryParameter("webui_token"), origin.fragment
                    )).filter { it.isNotBlank() }
                    val decoded = Uri.decode(uri.toString())
                    if (secrets.any { decoded.contains(it) }) return false
                    return true
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!allowed(request.url)) return true
                    if (!sameOrigin(request.url) && request.isForMainFrame) {
                        // 新顶层导航不继承本地页 Referer，避免查询参数中的令牌跟随验证跳转。
                        view.loadUrl(request.url.toString())
                        return true
                    }
                    return false
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val sensitiveHeader = !sameOrigin(request.url) && request.requestHeaders.values.any { header ->
                        tokens.filter { it.isNotBlank() }.any { Uri.decode(header).contains(it) }
                    }
                    if (allowed(request.url) && !sensitiveHeader) return null
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
            }
            val local = origin.host in setOf("127.0.0.1", "localhost", "::1", "[::1]")
            if (local && origin.scheme in setOf("http", "https")) loadUrl(url)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
    }
    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onDismiss()
    }
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Text(accountStatus, modifier = Modifier.padding(horizontal = Spacing.lg))
            TextButton(onClick = onRefresh, enabled = refreshEnabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.napcat_refresh_qr))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md)) {
                Text(stringResource(R.string.common_close))
            }
            BrowserWebView(webView, Modifier.fillMaxWidth().weight(1f))
        }
    }
}
