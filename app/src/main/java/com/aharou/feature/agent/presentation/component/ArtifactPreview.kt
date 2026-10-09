package com.aharou.feature.agent.presentation.component

import android.annotation.SuppressLint
import android.content.ClipData
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.semanticColors
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.mikepenz.markdown.compose.LocalMarkdownColors
import compose.icons.FeatherIcons
import compose.icons.feathericons.Code
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Eye
import compose.icons.feathericons.Save
import compose.icons.feathericons.X
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ==================== 产物识别 ====================

/** 聊天里可渲染的产物类型（本轮只覆盖完整 HTML 文档与完整 SVG）。 */
internal enum class ArtifactKind { HTML, SVG }

/** 一段被判定为产物的代码块。 */
internal data class CodeArtifact(
    val kind: ArtifactKind,
    val code: String,
    val language: String?,
)

/** 只认这些语言标签；无标签时按内容判定。 */
private val ARTIFACT_LANGUAGES = setOf("html", "htm", "xhtml", "svg", "xml")

/** 超过该长度的代码块不当产物（避免把整篇日志/生成代码塞进 WebView）。 */
private const val MAX_ARTIFACT_CHARS = 400_000

/** 小于该长度的代码块不可能是完整文档。 */
private const val MIN_ARTIFACT_CHARS = 40

/**
 * 保守判定一段代码块是否为可渲染产物——拿不准一律返回 null（宁漏勿误判）。
 *
 * - 语言标签非空时必须是 [ARTIFACT_LANGUAGES] 之一，普通代码（如 kotlin/python/json）直接排除；
 * - 内容必须以 `<` 起头（跳过前导空白），普通代码片段排除；
 * - HTML：以 `<!doctype` 或 `<html` 起头，且必须含 `</html>`（保证是完整文档，排除片段）；
 * - SVG：可带 `<?xml …?>` 前导，核心以 `<svg` 起头，且必须含 `</svg>`。
 */
internal fun detectCodeArtifact(language: String?, code: String): CodeArtifact? {
    if (code.length < MIN_ARTIFACT_CHARS || code.length > MAX_ARTIFACT_CHARS) return null
    val lang = language?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    if (lang != null && lang !in ARTIFACT_LANGUAGES) return null
    val start = code.indexOfFirst { !it.isWhitespace() }
    if (start < 0 || code[start] != '<') return null
    val body = code.substring(start)
    val head = body.substring(0, minOf(body.length, 160)).lowercase()
    if (head.startsWith("<!doctype") || head.startsWith("<html")) {
        return if (body.contains("</html>", ignoreCase = true)) CodeArtifact(ArtifactKind.HTML, code, lang) else null
    }
    val svgBody = if (head.startsWith("<?xml")) body.substringAfter("?>", body).trimStart() else body
    val svgHead = svgBody.take(64).lowercase()
    if (svgHead.startsWith("<svg") || svgHead.startsWith("<!doctype svg")) {
        return if (svgBody.contains("</svg>", ignoreCase = true)) CodeArtifact(ArtifactKind.SVG, code, lang) else null
    }
    return null
}

// ==================== 消息里的入口 ====================

/**
 * 消息代码块的渲染分支：是产物就在代码块上方补一个「预览」胶囊，否则与改动前完全一致。
 * 判定结果按 (language, code) 记忆化，流式增量时不必每帧重跑识别。
 */
@Composable
internal fun ArtifactAwareCodeBlock(
    code: String,
    language: String?,
    style: TextStyle,
    highlightsBuilder: Highlights.Builder,
    showHeader: Boolean,
    cacheEnabled: Boolean,
) {
    val artifact = remember(language, code) { detectCodeArtifact(language, code) }
    if (artifact == null) {
        SafeMarkdownHighlightedCode(code, language, style, highlightsBuilder, showHeader, cacheEnabled)
    } else {
        Column(modifier = Modifier.fillMaxWidth()) {
            ArtifactPreviewChip(artifact = artifact, modifier = Modifier.padding(start = 8.dp, top = 8.dp))
            SafeMarkdownHighlightedCode(code, language, style, highlightsBuilder, showHeader, cacheEnabled)
        }
    }
}

@Composable
private fun ArtifactPreviewChip(artifact: CodeArtifact, modifier: Modifier = Modifier) {
    Surface(
        onClick = { ArtifactPreviewState.open(artifact) },
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = LocalMarkdownColors.current.codeBackground,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = FeatherIcons.Eye,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.common_preview),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (artifact.kind == ArtifactKind.SVG) "SVG" else "HTML",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.semanticColors.subtleText,
            )
        }
    }
}

// ==================== 入口（消息 → 全屏预览宿主）====================

/**
 * 产物预览的触发点。消息里的代码块点「预览」时写入，[AIChatPanel] 顶部渲染的
 * [ArtifactPreviewStateHost] 消费。用单例而非组合局部的理由：入口深埋在 LazyColumn 的
 * 消息 item 里，够不到能覆盖整屏的宿主；而 WebView 必须画在主窗口（见 [ArtifactWebView] 注释），
 * 不能挂进 Dialog/ModalBottomSheet 的独立窗口。
 */
internal object ArtifactPreviewState {
    private val _target = MutableStateFlow<CodeArtifact?>(null)
    val target: StateFlow<CodeArtifact?> = _target.asStateFlow()

    fun open(artifact: CodeArtifact) {
        _target.value = artifact
    }

    fun dismiss() {
        _target.value = null
    }
}

/** 全屏预览宿主；由 [AIChatPanel] 在 Scaffold 之后调用一次即可（同 [com.aharou.core.ui.ImageViewerHost]）。 */
@Composable
internal fun ArtifactPreviewStateHost() {
    val target by ArtifactPreviewState.target.collectAsStateWithLifecycle()
    target?.let { artifact ->
        ArtifactPreviewHost(artifact = artifact, onDismiss = { ArtifactPreviewState.dismiss() })
    }
}

@Composable
private fun ArtifactPreviewHost(artifact: CodeArtifact, onDismiss: () -> Unit) {
    BackHandler { onDismiss() }
    var showCode by remember(artifact) { mutableStateOf(false) }
    var error by remember(artifact) { mutableStateOf<String?>(null) }

    val appContext = LocalContext.current.applicationContext
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val fileAccess = remember(appContext) {
        EntryPointAccessors.fromApplication(appContext, ArtifactSaveEntryPoint::class.java).fileAccessProvider()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(FeatherIcons.X, contentDescription = stringResource(R.string.common_close), tint = MaterialTheme.colorScheme.onSurface)
                }
                Text(
                    text = stringResource(R.string.common_preview),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = { showCode = !showCode }) {
                    Icon(
                        imageVector = if (showCode) FeatherIcons.Eye else FeatherIcons.Code,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(if (showCode) R.string.common_preview else R.string.artifact_view_code))
                }
                IconButton(onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("artifact", artifact.code)))
                        Toast.makeText(appContext, appContext.getString(R.string.common_copy_success), Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(FeatherIcons.Copy, contentDescription = stringResource(R.string.common_copy), tint = MaterialTheme.colorScheme.onSurface)
                }
                IconButton(onClick = {
                    scope.launch {
                        val message = runCatching { saveArtifact(fileAccess, artifact) }.fold(
                            onSuccess = { appContext.getString(R.string.artifact_saved_to, it) },
                            onFailure = { appContext.getString(R.string.file_save_failed_toast) },
                        )
                        Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(FeatherIcons.Save, contentDescription = stringResource(R.string.common_save), tint = MaterialTheme.colorScheme.onSurface)
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.semanticColors.subtleBorder)
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    showCode -> ArtifactCodeView(code = artifact.code, language = artifact.language)
                    error != null -> ArtifactRenderError(onViewCode = { showCode = true })
                    else -> ArtifactWebView(artifact = artifact, onLoadError = { error = it })
                }
            }
        }
    }
}

/** 「查看代码」视图：复用消息里同一套高亮引擎（[buildHighlightedText]）与等宽字体。 */
@Composable
private fun ArtifactCodeView(code: String, language: String?, modifier: Modifier = Modifier) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val highlightsBuilder = remember(isDark) { Highlights.Builder().theme(SyntaxThemes.atom(darkMode = isDark)) }
    val highlighted by produceState(initialValue = AnnotatedString(code), code, language, isDark) {
        value = withContext(Dispatchers.Default) { buildHighlightedText(code, language, highlightsBuilder) }
    }
    Text(
        text = highlighted,
        style = TextStyle(fontFamily = CodeFontFamily, fontSize = 13.sp, lineHeight = 19.sp),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    )
}

@Composable
private fun ArtifactRenderError(onViewCode: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.artifact_preview_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onViewCode) { Text(stringResource(R.string.artifact_view_code)) }
    }
}

// ==================== 沙箱 WebView ====================

/**
 * 把产物交给沙箱 WebView 渲染：只加载内容本身，页面拿不到任何原生接口。
 *
 * 安全设置（对照 `MarkdownPreviewWebView` 的既有做法）：
 * - JS 开：图表/交互类产物（eCharts、Mermaid、内联脚本）离了 JS 就是静态文本，产物预览的核心场景需要它；
 * - 文件访问 / content 访问关：`allowFileAccess`、`allowContentAccess` 置 false，页面读不到 `file://` 与 `content://`；
 * - 跨源文件读关：`allowFileAccessFromFileURLs`、`allowUniversalAccessFromFileURLs` 显式置 false（纵深防御）；
 * - 混合内容禁：`MIXED_CONTENT_NEVER_ALLOW`，https 页面里的 http 子资源被拦；
 * - DOM Storage 关：与项目 Markdown 预览一致，沙箱不需要持久化；
 * - 不注册 JS 接口：全程不调用 `addJavascriptInterface`，页面无法触达原生；
 * - 导航拦截：`shouldOverrideUrlLoading` 一律返回 true，点页面里的链接不会把预览导航走；
 * - 底色固定白：产物（尤其 SVG）多按白底黑字书写，跟随深色主题反而会让内容不可见。
 *
 * 已知取舍：https 子资源（CDN 上的图表库、外链图片）仍会走网络——这是「图表」场景能渲染的前提，
 * 在此明示；若日后要彻底断网，在 `shouldInterceptRequest` 里统一挡掉即可。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ArtifactWebView(artifact: CodeArtifact, onLoadError: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val html = remember(artifact) { artifactHtml(artifact) }
    val webView = remember(artifact) {
        WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                domStorageEnabled = false
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(false)
                setGeolocationEnabled(false)
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
            }
            setBackgroundColor(0xFFFFFFFF.toInt())
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) onLoadError(error.description?.toString().orEmpty())
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    onLoadError("render process gone")
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

    LaunchedEffect(webView, html) {
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    AndroidView(factory = { webView }, modifier = modifier)
}

private fun artifactHtml(artifact: CodeArtifact): String = when (artifact.kind) {
    ArtifactKind.HTML -> artifact.code
    ArtifactKind.SVG -> SVG_SHELL.replace("__SVG__", stripSvgProlog(artifact.code))
}

/** 去掉 SVG 的 XML 声明 / DOCTYPE——内联进 HTML 后这两段既不合法也无需保留。 */
private fun stripSvgProlog(code: String): String {
    var s = code.trimStart()
    if (s.startsWith("<?xml", ignoreCase = true)) s = s.substringAfter("?>", s).trimStart()
    if (s.startsWith("<!doctype", ignoreCase = true)) s = s.substringAfter(">", s).trimStart()
    return s
}

private const val SVG_SHELL = """<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<style>html,body{margin:0;padding:0;background:#ffffff}body{padding:12px;display:flex;justify-content:center}svg{max-width:100%;height:auto}</style>
</head><body>__SVG__</body></html>"""

// ==================== 保存到工作区 ====================

private const val WORKSPACE_ROOT = "~/workspace"

/** 保存到当前工作区根，沿用 AI「写文件」的 `~/workspace/…` 路径约定；重名自动加后缀。 */
private suspend fun saveArtifact(access: FileAccessProvider, artifact: CodeArtifact): String = withContext(Dispatchers.IO) {
    val ext = if (artifact.kind == ArtifactKind.SVG) "svg" else "html"
    var path = "$WORKSPACE_ROOT/artifact.$ext"
    var index = 2
    while (access.exists(path)) {
        path = "$WORKSPACE_ROOT/artifact-$index.$ext"
        index++
    }
    access.writeFile(path, artifact.code, overwrite = false)
    path
}

/** Composable 取不到注入单例（没有 ViewModel 入口），只能走 EntryPoint（同 SharedIntakeEntryPoint）。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ArtifactSaveEntryPoint {
    fun fileAccessProvider(): FileAccessProvider
}
