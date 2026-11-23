package com.aharou.feature.agent.domain.tool.search

import com.aharou.core.net.UrlSafetyPolicy
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import javax.inject.Inject

class WebFetchTool @Inject constructor() : AgentTool() {

    private companion object {
        const val TAG = "WebFetchTool"
        // 较新的桌面 Chrome 版本，搭配下方 sec-ch-ua / sec-fetch-* 请求头以贴近真实浏览器指纹
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

        /**
         * 单次默认返回的字符数：约 5k token。够模型判断这一页有没有用、值不值得继续读，
         * 又不至于一口气吃掉整轮预算；不够再按 next_offset 续读，同一轮内续读不重抓网。
         */
        const val DEFAULT_MAX_CHARS = 20_000

        /** 单次返回的字符数上限：外层 ToolOutputStore 的内联预算是 40k 字符，超过它反正还要再截一次。 */
        const val MAX_CHARS_LIMIT = 40_000

        /** 手动跟随重定向的上限：每跳都要重做主机校验，防止用跳转绕过内网拦截。 */
        const val MAX_REDIRECTS = 5

        /** [URI] 解析不出 host 时的兜底提取（含端口/方括号）。 */
        val HOST_FALLBACK = Regex("^https?://([^/?#]+)", RegexOption.IGNORE_CASE)

        /** 拿不到会话上下文时的兜底键：此时快照只能靠总量淘汰清理，不会有轮次内清理。 */
        const val NO_SESSION = "no-session"
        const val NO_TURN = "no-turn"
    }

    override val name = "webfetch"
    override val description = "抓取指定 HTTP/HTTPS 网页内容。支持提取网页正文为纯文本或返回原始 HTML 结构。" +
        "内容超长时按 max_chars 分段返回，带 document_id + next_offset 在同一轮内续读，不用重新抓网。"
    override val capabilities = setOf(ToolCapability.NETWORK_READ)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "url" to ToolParameter(
            name = "url",
            type = ParameterType.STRING,
            description = "需要抓取的网页完整 URL (必须以 http:// 或 https:// 开头)。首次抓取必填；带 document_id 续读时不用给。",
            required = false
        ),
        "format" to ToolParameter(
            name = "format",
            type = ParameterType.STRING,
            description = "返回格式：text（默认，去除广告/脚本/样式，仅保留正文）或 html（原始 HTML 源码）；续读时忽略，按首次抓取的格式返回",
            enum = listOf("text", "html"),
            required = false
        ),
        "document_id" to ToolParameter(
            name = "document_id",
            type = ParameterType.STRING,
            description = "续读用：上一次 webfetch 返回的 document_id，配合 offset 直接从快照切片，不再抓网",
            required = false
        ),
        "offset" to ToolParameter(
            name = "offset",
            type = ParameterType.INTEGER,
            description = "续读起点：字符偏移，通常直接填上一次返回的 next_offset",
            required = false
        ),
        "max_chars" to ToolParameter(
            name = "max_chars",
            type = ParameterType.INTEGER,
            description = "本次最多返回多少字符（默认 $DEFAULT_MAX_CHARS，上限 $MAX_CHARS_LIMIT）",
            required = false
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult = executeInternal(args, sessionKey = NO_SESSION, turnKey = NO_TURN)

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        // 快照按「会话 + 本轮用户消息」隔离：同一轮里的多次工具调用能互相续读，下一轮自动失效。
        val sessionKey = context.sessionId?.takeIf { it.isNotBlank() } ?: NO_SESSION
        val turnKey = "$sessionKey@${context.inputMessageId.ifBlank { NO_TURN }}"
        return executeInternal(args, sessionKey, turnKey)
    }

    private suspend fun executeInternal(args: Map<String, JsonElement>, sessionKey: String, turnKey: String): ToolResult {
        val documentId = args["document_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val rawMaxChars = args["max_chars"]?.jsonPrimitive?.intOrNull
        if (rawMaxChars != null && rawMaxChars < 1) {
            return ToolResult.Error("max_chars 必须是正整数（本次给的是 $rawMaxChars）", "INVALID_ARGUMENT")
        }
        val maxChars = (rawMaxChars ?: DEFAULT_MAX_CHARS).coerceAtMost(MAX_CHARS_LIMIT)
        val offset = args["offset"]?.jsonPrimitive?.intOrNull ?: 0
        if (offset < 0) return ToolResult.Error("offset 不能为负数（本次给的是 $offset）", "INVALID_ARGUMENT")

        if (documentId != null) {
            FileLogger.i(TAG, "续读网页快照: $documentId, offset=$offset, max_chars=$maxChars")
            return respond(documentId, offset, maxChars, sessionKey, turnKey)
        }

        val url = args["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (url.isEmpty()) {
            return ToolResult.Error(
                "缺少 url 参数：首次抓取必须给 url；要从上一份结果续读请用 document_id + offset",
                "INVALID_ARGUMENT"
            )
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return ToolResult.Error("URL 必须以 http:// 或 https:// 开头")
        }
        blockedReason(url)?.let { return ToolResult.Error(it) }

        val format = args["format"]?.jsonPrimitive?.contentOrNull ?: "text"

        return withContext(Dispatchers.IO) {
            try {
                FileLogger.i(TAG, "正在抓取网页: $url, format=$format")

                // 从顶层捕获非 HTTP 的连接异常（DNS、超时、SSL 等），直接把具体原因回传给 AI，不做兜底猜测
                val doc = try {
                    fetchDocument(url)
                } catch (e: FetchException) {
                    return@withContext ToolResult.Error(e.detailedMessage(url))
                }

                val resultText = when (format) {
                    "html" -> doc.outerHtml()
                    else -> extractCleanText(doc)
                }

                val captured = WebFetchSnapshots.capture(resultText, sessionKey, turnKey, url)
                respond(captured.id, 0, maxChars, sessionKey, turnKey)
            } catch (e: Exception) {
                FileLogger.e(TAG, "抓取网页时发生异常", e)
                ToolResult.Error("抓取失败: ${e.message}")
            }
        }
    }

    /** 从快照切一段给模型，并带上续读需要的一切信息。 */
    private fun respond(
        documentId: String,
        offset: Int,
        maxChars: Int,
        sessionKey: String,
        turnKey: String
    ): ToolResult {
        return when (val slice = WebFetchSnapshots.slice(documentId, sessionKey, turnKey, offset, maxChars)) {
            WebFetchSnapshots.Slice.Unknown -> ToolResult.Error(
                "找不到 document_id=$documentId 的快照。可能抄错了 id；请用 url 重新抓取一次。",
                "SNAPSHOT_NOT_FOUND"
            )

            WebFetchSnapshots.Slice.Expired -> ToolResult.Error(
                "document_id=$documentId 的快照已失效：快照只在同一轮对话内有效，上一轮结束就清理了。" +
                    "请用 url 重新抓取；重新抓取只是这一次要走网络，后续仍可用新的 document_id 续读。",
                "SNAPSHOT_EXPIRED"
            )

            is WebFetchSnapshots.Slice.OutOfRange -> ToolResult.Error(
                "offset=$offset 超出范围：这份文档只抓到了 ${slice.totalChars} 个字符。请用 0 到 ${slice.totalChars} 之间的 offset。",
                "OFFSET_OUT_OF_RANGE"
            )

            is WebFetchSnapshots.Slice.Ok -> buildSliceResult(slice)
        }
    }

    private fun buildSliceResult(slice: WebFetchSnapshots.Slice.Ok): ToolResult {
        val doc = slice.doc
        val hasMore = slice.end < doc.totalChars
        val truncatedReason = when {
            hasMore -> "max_chars"
            doc.sourceTruncated -> "source_truncated"
            else -> "end_of_document"
        }
        val result = mutableMapOf<String, JsonElement>(
            "content" to JsonPrimitive(slice.text),
            "document_id" to JsonPrimitive(doc.id),
            "offset" to JsonPrimitive(slice.start),
            "returned_chars" to JsonPrimitive(slice.end - slice.start),
            "total_chars" to JsonPrimitive(doc.totalChars),
            "next_offset" to if (hasMore) JsonPrimitive(slice.end) else JsonNull,
            "truncated_reason" to JsonPrimitive(truncatedReason)
        )
        when {
            hasMore -> result["note"] = JsonPrimitive(
                "后面还有 ${doc.totalChars - slice.end} 个字符。用 " +
                    "webfetch(document_id=\"${doc.id}\", offset=${slice.end}) 继续读，不用重新抓网。"
            )

            doc.sourceTruncated -> result["note"] = JsonPrimitive(
                "源正文超过单份快照上限（${WebFetchSnapshots.MAX_SNAPSHOT_CHARS} 个字符），" +
                    "快照只留了前 ${doc.totalChars} 个字符，剩余内容无法续读。" +
                    "需要后文请改用 browser 工具，或抓更具体的章节页。"
            )

            slice.text.isEmpty() -> result["note"] = JsonPrimitive("offset 正好在文档末尾，本次没有内容可返回。")
        }
        return ToolResult.Success(JsonObject(result))
    }

    /**
     * 目标主机命中禁用网段时返回给模型的拒绝文案；允许（或无法判定）返回 null。
     * 判定前先解析出主机名——[URI] 对含特殊字符的 URL 会抛异常，此时退回正则提取。
     */
    private fun blockedReason(url: String): String? {
        val host = runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: HOST_FALLBACK.find(url)?.groupValues?.get(1)?.let { raw ->
                if (raw.startsWith("[")) raw.substringAfter("[").substringBefore("]")
                else raw.substringBefore(":")
            }
            ?: return null
        return if (UrlSafetyPolicy.isBlockedHost(host)) {
            "拒绝访问本机或内网地址：$host"
        } else {
            null
        }
    }

    private fun fetchDocument(url: String): Document {
        var current = url
        var redirects = 0
        while (true) {
            val response = try {
                Jsoup.connect(current)
                    .userAgent(USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    // 真桌面 Chrome 一定会发送的 sec-ch-ua 系列客户端提示
                    .header("Sec-Ch-Ua", "\"Chromium\";v=\"132\", \"Not A(Brand\";v=\"99\", \"Google Chrome\";v=\"132\"")
                    .header("Sec-Ch-Ua-Mobile", "?0")
                    .header("Sec-Ch-Ua-Platform", "\"Windows\"")
                    // Fetch Metadata 请求头，现代浏览器发页面请求时必带
                    .header("Sec-Fetch-Dest", "document")
                    .header("Sec-Fetch-Mode", "navigate")
                    .header("Sec-Fetch-Site", "none")
                    .header("Sec-Fetch-User", "?1")
                    .header("Upgrade-Insecure-Requests", "1")
                    // 关掉自动跟随：跳转目标要在下一圈重新过主机校验，否则能被 302 到内网绕过拦截
                    .followRedirects(false)
                    .ignoreHttpErrors(true)
                    .maxBodySize(5 * 1024 * 1024) // 5MB
                    .timeout(15000) // 15秒超时
                    .ignoreContentType(true)
                    .execute()
            } catch (e: org.jsoup.UnsupportedMimeTypeException) {
                FileLogger.e(TAG, "不支持的响应类型: ${e.getMimeType()} - ${e.getUrl()}", e)
                throw FetchException("不支持的响应 MIME 类型: ${e.getMimeType()}")
            } catch (e: java.net.SocketTimeoutException) {
                FileLogger.e(TAG, "请求超时: $current", e)
                throw FetchException("请求超时（15 秒内未响应）")
            } catch (e: java.net.UnknownHostException) {
                FileLogger.e(TAG, "DNS 解析失败: $current", e)
                throw FetchException("无法解析主机名：$current")
            } catch (e: javax.net.ssl.SSLException) {
                FileLogger.e(TAG, "SSL 握手失败: $current", e)
                throw FetchException("SSL/TLS 握手失败：${e.message ?: "未知原因"}")
            } catch (e: java.io.IOException) {
                FileLogger.e(TAG, "网络 I/O 异常: ${e.message} - $current", e)
                throw FetchException("网络 I/O 异常：${e.message ?: "未知原因"}")
            }

            val status = response.statusCode()
            if (status in 300..399) {
                val location = response.header("Location")
                    ?: throw FetchException("HTTP $status 重定向缺少 Location 头")
                if (++redirects > MAX_REDIRECTS) {
                    throw FetchException("重定向次数过多（超过 $MAX_REDIRECTS 次）")
                }
                current = runCatching { URI(current).resolve(location).toString() }
                    .getOrElse { throw FetchException("重定向地址无法解析：$location") }
                blockedReason(current)?.let { throw FetchException(it) }
                continue
            }
            if (status >= 400) {
                FileLogger.e(TAG, "HTTP 状态码异常: $status - $current")
                throw FetchException("HTTP $status: ${response.statusMessage().ifBlank { "无状态描述" }}")
            }
            return response.parse()
        }
    }

    /** 把抓取过程中的失败包装成携带具体信息的异常，以便回传给 AI 而非模糊提示。 */
    private class FetchException(val detail: String) : RuntimeException(detail) {
        fun detailedMessage(url: String): String = "抓取 $url 失败：$detail"
    }

    /**
     * 提取干净正文，并在段落之间保留换行符。
     */
    private fun extractCleanText(doc: Document): String {
        // 移除多余的不可见内容
        doc.select("script, style, iframe, nav, footer, header, noscript, .ad, .advertisement").remove()

        // 为了避免 Jsoup 的 .text() 把所有行挤在一起，给块级元素加上换行符
        doc.select("p, h1, h2, h3, h4, h5, h6, li, div, br").append("\\n")

        val rawText = doc.body()?.text() ?: ""

        // 还原换行符，并清理多余的空行
        return rawText.replace("\\n", "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}
