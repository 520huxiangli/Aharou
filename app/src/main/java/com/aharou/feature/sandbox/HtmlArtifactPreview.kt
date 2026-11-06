package com.aharou.feature.sandbox

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aharou.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 自包含 HTML 渲染：先内联资源再交给 WebView，网络与文件访问全关。
 *
 * 抽成公共组件是因为两个入口都要用：沙盒文件浏览器的预览页，以及聊天里点开的
 * HTML 附件。两边如果各写一份 WebView 配置，安全开关迟早会漂移。
 */
@Composable
internal fun BundledHtmlView(
    file: File,
    modifier: Modifier = Modifier,
    showNotice: Boolean = true,
) {
    // 内联要读一堆资源文件，放主线程会卡住首帧；按文件缓存，切回来不必重算。
    val bundled by produceState<HtmlArtifactBundler.Bundled?>(null, file) {
        value = withContext(Dispatchers.IO) { HtmlArtifactBundler.bundle(file) }
    }
    Column(modifier = modifier) {
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    // 页面已经自包含：行内样式与脚本要能跑，文件系统与网络都不需要，索性全关。
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true
                    settings.domStorageEnabled = false
                    // 报告里的宽表格装不下屏宽时会被切掉两侧，允许双指放大比强制缩放页面阅读体验好。
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    // T-webview-popup-d3c6e10f: mirror ffc85ad's WebPreviewBottomSheet fix。
                    // `height: 100vh` + overflow 隐藏的页面在首帧会因 CSS 视口按 0×0 测量
                    // 而塌成白屏；这两项把 CSS 视口与初始测量尺寸解耦。
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    webViewClient = object : WebViewClient() {
                        // 产物是「看结果」不是「逛网页」：除 about: 之外一律拦下。
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean = request?.url?.toString()?.startsWith("about:") != true
                    }
                }
            },
            update = { webView ->
                val ready = bundled ?: return@AndroidView
                // tag 存上次载入的内容，避免重组反复 loadDataWithBaseURL 把滚动打回顶部。
                if (webView.tag != ready.html) {
                    webView.tag = ready.html
                    webView.loadDataWithBaseURL(null, ready.html, "text/html", "utf-8", null)
                }
            },
        )
        val info = bundled
        if (!showNotice || info == null) return@Column
        val notice = when {
            info.incomplete -> stringResource(R.string.filepreview_html_incomplete)
            info.skippedExternal > 0 ->
                stringResource(R.string.filepreview_html_skipped_external, info.skippedExternal)
            else -> null
        } ?: return@Column
        Text(
            text = notice,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * 聊天里点开的 HTML 附件：全屏弹层预览。
 *
 * 走应用内而不是 ACTION_VIEW，是因为 FileProvider 给的是 content:// 单文件，
 * 页面里的相对样式/图片在外部浏览器里全部断链，而这恰恰是产物最常见的样子。
 */
@Composable
internal fun HtmlArtifactPreviewDialog(file: File, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.common_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                BundledHtmlView(file = file, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
