package com.aharou

import androidx.compose.ui.platform.UriHandler

/**
 * 聊天区 Markdown 链接处理（从 MainActivity 抽出，控制单文件体量）：
 * - `http` / `https` 网址 → [onOpenUrl]（内置浏览器预览）；
 * - 本地 `.html` / `.htm` 文件 → 经 [toFileUrl] 解析为 `file://` 后交 [onOpenUrl]（内置浏览器渲染）；
 * - 其它容器文件路径 → [onOpenFile] 打开编辑器；
 * - `mailto` / `tel` 等非 http 网址 → 交系统默认 [delegate]。
 *
 * 约定：AI 用 `[显示文本](路径)` 输出可点击路径，路径可带 `:行号` 后缀（如 `~/workspace/a.kt:42`）。
 */
internal class FileAwareUriHandler(
    private val delegate: UriHandler,
    private val onOpenUrl: (String) -> Unit,
    private val toFileUrl: (path: String) -> String?,
    private val onOpenFile: (path: String, line: Int) -> Unit
) : UriHandler {
    override fun openUri(uri: String) {
        // http/https 网址 → 内置浏览器（此前是跳系统外部浏览器）。
        if (WEB_URL_SCHEME.containsMatchIn(uri)) {
            onOpenUrl(uri)
            return
        }
        val filePath = asChatFilePath(uri)
        if (filePath == null) {
            delegate.openUri(uri)
            return
        }
        val (path, line) = splitPathAndLine(filePath)
        // 本地 HTML 文件 → 内置浏览器渲染预览；解析不到真实文件时退回编辑器。
        if (isHtmlPath(path)) {
            toFileUrl(path)?.let { url ->
                onOpenUrl(url)
                return
            }
        }
        onOpenFile(path, line)
    }
}

/** `http://` / `https://` 开头的网址（大小写不敏感）。 */
private val WEB_URL_SCHEME = Regex("^https?://", RegexOption.IGNORE_CASE)

/** 本地 HTML 文件扩展名。 */
private val HTML_EXTENSIONS = setOf("html", "htm")

/** 是否本地 HTML 文件（按扩展名判定，先剥掉 query / fragment）。 */
internal fun isHtmlPath(path: String): Boolean =
    path.substringBefore('?').substringBefore('#').substringAfterLast('.', "").lowercase() in HTML_EXTENSIONS

/** 带 `//` 的层级式 scheme：`http://`、`https://`、`file://`、`content://` 等。 */
private val HIERARCHICAL_SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*)://")

/**
 * 不带 `//` 的网址型 scheme 只能白名单枚举：scheme 语法允许 `.`，因此工作区根目录下的
 * 文件链接 `notes.md:12` 与 `mailto:x` 在语法上无从区分，按「有冒号即网址」判定会让
 * 这类链接被丢给浏览器而点不开。
 */
private val OPAQUE_URL_SCHEME = Regex(
    "^(mailto|tel|sms|smsto|geo|data|market|intent|about|javascript):",
    RegexOption.IGNORE_CASE
)

private val TRAILING_LINE = Regex("^(.*):(\\d+)$")

/** 聊天区链接目标 → 归一化后的文件路径；判定为网址（非 `file` scheme）时返回 null。 */
internal fun asChatFilePath(uri: String): String? {
    val hierarchical = HIERARCHICAL_SCHEME.find(uri)?.groupValues?.get(1)?.lowercase()
    return when {
        hierarchical == "file" -> android.net.Uri.decode(uri.removePrefix("file://"))
        hierarchical != null -> null // http/https/content 等交给系统
        OPAQUE_URL_SCHEME.containsMatchIn(uri) -> null // mailto/tel 等
        else -> uri // 容器绝对路径或相对工作区路径，含 `notes.md:12` 这种纯文件名
    }
}

/** 拆出可选的 `:行号` 后缀；无则行号为 0。 */
internal fun splitPathAndLine(path: String): Pair<String, Int> {
    val cleaned = path.substringBefore('#').trim()
    val m = TRAILING_LINE.find(cleaned) ?: return cleaned to 0
    return m.groupValues[1] to (m.groupValues[2].toIntOrNull() ?: 0)
}
