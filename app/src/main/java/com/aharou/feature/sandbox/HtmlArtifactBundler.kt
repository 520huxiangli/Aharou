package com.aharou.feature.sandbox

import android.util.Base64
import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Element
import java.io.File

/**
 * 把 HTML 产物打成自包含页面：样式、脚本、图片全部内联，外部 http(s) 引用剥掉。
 *
 * 为什么不直接 `loadUrl("file://…")`：那样页面要靠 WebView 的本地文件访问去取相对资源，
 * 等于给产物开了读整个文件系统的口子，而为了堵这个口子又只能把 JS 关掉——图表与交互全废。
 * 内联之后再配 CSP，JS 能跑、网络与文件访问都堵死，断网也能看到和作者预期一致的渲染。
 */
internal object HtmlArtifactBundler {

    /** 内联后的总字节上限：再大就不要继续往里塞，避免一个产物把 WebView 内存撑爆。 */
    private const val MAX_BUNDLED_BYTES = 20 * 1024 * 1024

    /** 单个被内联资源的上限；超了多半是视频或大图，内联进来也不该塞给预览。 */
    private const val MAX_INLINE_FILE_BYTES = 4 * 1024 * 1024

    /** 页面能用的能力：只允许内联样式/脚本与 data: 资源，网络、框架、表单、base 全禁。 */
    private const val CONTENT_SECURITY_POLICY =
        "default-src 'none'; img-src data: blob:; media-src data: blob:; " +
            "font-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline' 'unsafe-eval'; " +
            "connect-src 'none'; frame-src 'none'; object-src 'none'; " +
            "base-uri 'none'; form-action 'none'"

    private val CSS_URL = Regex("""url\(\s*(['"]?)([^'")]+)\1\s*\)""")

    data class Bundled(
        val html: String,
        val inlinedFiles: Int,
        val skippedExternal: Int,
        /** 有资源因为超限或读失败没内联进去，预览可能不完整。 */
        val incomplete: Boolean,
    )

    fun bundle(entry: File): Bundled? {
        // 先看体积再读：一个几十兆的 HTML 读进内存只为发现装不下，这一步没必要走。
        if (entry.length() > MAX_BUNDLED_BYTES) return null
        val raw = runCatching { entry.readText() }.getOrNull() ?: return null
        val baseDir = entry.parentFile ?: return null
        return bundle(raw, baseDir)
    }

    fun bundle(rawHtml: String, baseDir: File): Bundled {
        val doc = Jsoup.parse(rawHtml, baseDir.absolutePath)
        var inlined = 0
        var skipped = 0
        var incomplete = false
        var budget = MAX_BUNDLED_BYTES

        /**
         * 只解析到基准目录之内：绝对路径与 file: 一律不认，产物应当用相对引用；
         * canonicalFile 之后再比前缀，`../` 与符号链接都绕不出去。
         */
        fun resolve(base: File, ref: String): File? {
            val clean = ref.substringBefore('#').substringBefore('?').trim()
            if (clean.isEmpty() || clean.startsWith("/") || clean.startsWith("file:")) return null
            return runCatching {
                val root = base.canonicalFile
                val target = File(root, clean).canonicalFile
                if (target.path == root.path || target.path.startsWith(root.path + File.separator)) {
                    target
                } else {
                    null
                }
            }.getOrNull()
        }

        fun read(base: File, ref: String): ByteArray? {
            val file = resolve(base, ref) ?: return null
            if (!file.isFile) return null
            val length = file.length()
            if (length <= 0L || length > MAX_INLINE_FILE_BYTES || length > budget) {
                incomplete = true
                return null
            }
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes == null) {
                incomplete = true
                return null
            }
            budget -= bytes.size
            return bytes
        }

        fun dataUrl(bytes: ByteArray, name: String): String =
            "data:${mimeOf(name)};base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"

        /** CSS 里的 url() 也要换掉，否则背景图与图标字体在内联样式里依然缺失。 */
        fun inlineCssUrls(css: String, cssBase: File): String =
            CSS_URL.replace(css) { match ->
                val ref = match.groupValues[2].trim()
                when {
                    ref.startsWith("data:") || ref.startsWith("#") -> match.value
                    isExternal(ref) -> {
                        skipped++
                        match.value
                    }
                    else -> {
                        // CSS 内联后页面基准是 HTML 所在目录，引用要按 CSS 原位置解析才对。
                        val bytes = read(cssBase, ref) ?: return@replace match.value
                        inlined++
                        "url(${dataUrl(bytes, ref)})"
                    }
                }
            }

        // 1. 外链样式表 → 行内 <style>
        // 按 rel 子串判定而不是 `rel~=stylesheet`：后者的正则要求整个属性值完全匹配，
        // `rel="stylesheet preload"` 这类写法会被漏掉。
        doc.select("link[href]").toList()
            .filter { it.attr("rel").lowercase().contains("stylesheet") }
            .forEach { link ->
            val href = link.attr("href")
            if (isExternal(href)) {
                link.remove()
                skipped++
                return@forEach
            }
            val bytes = read(baseDir, href)
            if (bytes == null) {
                link.remove()
                return@forEach
            }
            val cssBase = File(baseDir, href.substringBefore('#').substringBefore('?')).parentFile ?: baseDir
            val style = Element("style")
            if (link.hasAttr("media")) style.attr("media", link.attr("media"))
            style.appendChild(DataNode(inlineCssUrls(bytes.toString(Charsets.UTF_8), cssBase)))
            link.replaceWith(style)
            inlined++
        }

        // 2. 行内 <style> 里的 url()
        doc.select("style").toList().forEach { style ->
            val css = style.data()
            if (css.isBlank()) return@forEach
            val replaced = inlineCssUrls(css, baseDir)
            if (replaced != css) {
                style.empty()
                style.appendChild(DataNode(replaced))
            }
        }

        // 3. 外链脚本 → 行内 <script>
        doc.select("script[src]").toList().forEach { script ->
            val src = script.attr("src")
            if (isExternal(src)) {
                script.remove()
                skipped++
                return@forEach
            }
            val bytes = read(baseDir, src)
            if (bytes == null) {
                script.remove()
                return@forEach
            }
            // integrity / crossorigin 必须丢：内联后的内容与原来的哈希对不上，
            // 浏览器会直接拒绝执行这段脚本，表现成「预览里图表全都不出来」。
            val inline = Element("script")
            script.attributes().forEach { attr ->
                if (attr.key != "src" && attr.key != "integrity" && attr.key != "crossorigin") {
                    inline.attr(attr.key, attr.value)
                }
            }
            inline.appendChild(DataNode(bytes.toString(Charsets.UTF_8)))
            script.replaceWith(inline)
            inlined++
        }

        // 4. 图片与媒体 → data:
        doc.select("img[src], source[src], video[poster], audio[src]").toList().forEach { el ->
            // srcset 是候选列表，浏览器优先用它；里面的相对路径不会被内联，留着就是破图。
            el.removeAttr("srcset")
            val attr = if (el.hasAttr("poster")) "poster" else "src"
            val ref = el.attr(attr)
            if (ref.isEmpty() || ref.startsWith("data:")) return@forEach
            if (isExternal(ref)) {
                el.removeAttr(attr)
                skipped++
                return@forEach
            }
            val bytes = read(baseDir, ref)
            if (bytes == null) {
                el.removeAttr(attr)
                return@forEach
            }
            el.attr(attr, dataUrl(bytes, ref))
            inlined++
        }

        // 5. 宽表格单独横向滑：手机屏宽装不下多列表格，整体缩又会让字小到看不见。
        //    外层包一个滚动容器，页面布局不变，只有表格自己能左右滑。
        doc.select("table").toList().forEach { table ->
            table.wrap("<div style=\"overflow-x:auto;-webkit-overflow-scrolling:touch\"></div>")
        }

        // 6. 图片与嵌入内容不溢出屏宽；产物常见的大截图会直接把页面撑宽。
        doc.head().appendChild(
            Element("style").appendChild(DataNode("img,video,svg,canvas{max-width:100%;height:auto}"))
        )

        // 7. CSP 放 head 最前：产物自带的脚本改不动已经生效的策略。
        doc.head().prependChild(
            Element("meta")
                .attr("http-equiv", "Content-Security-Policy")
                .attr("content", CONTENT_SECURITY_POLICY)
        )

        val html = doc.outerHtml()
        if (html.toByteArray(Charsets.UTF_8).size > MAX_BUNDLED_BYTES) incomplete = true
        return Bundled(html, inlined, skipped, incomplete)
    }

    private fun isExternal(ref: String): Boolean {
        val lower = ref.trim().lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("//") || lower.startsWith("file:")
    }

    private fun mimeOf(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "bmp" -> "image/bmp"
            "ico" -> "image/x-icon"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            else -> "application/octet-stream"
        }
}
