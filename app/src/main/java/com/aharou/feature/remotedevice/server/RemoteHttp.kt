package com.aharou.feature.remotedevice.server

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.nio.charset.StandardCharsets

/** 请求体超过上限时抛出，调用方回 413 并断开——避免局域网对端用一个超大 body 撑爆内存。 */
internal class HttpBodyTooLarge : Exception("request body too large")

/** 解析出来的一条 HTTP 请求（只取本服务需要的字段）。 */
internal class HttpRequest(
    val method: String,
    val target: String,
    val headers: Map<String, String>,
    val body: String
) {
    /** 去掉查询串的路径。 */
    val path: String get() = target.substringBefore('?')

    fun header(name: String): String? = headers[name.lowercase()]
}

/**
 * 极简 HTTP/1.1 读取：请求行 + 头部 + 按 Content-Length 的正文。
 * 与 `debugmcp/DebugMcpServer.kt` 同思路（不引第三方 HTTP server），差别只在多了正文上限。
 * 不支持 `Transfer-Encoding: chunked`——本服务的调用方是 Aharou 自己的 OkHttp 客户端，
 * 字符串正文固定带 Content-Length。
 */
internal fun readHttpRequest(input: InputStream, maxBodyBytes: Int): HttpRequest? {
    val requestLine = readLine(input) ?: return null
    if (requestLine.isBlank()) return null
    val parts = requestLine.split(" ")
    if (parts.size < 3) return null
    val headers = LinkedHashMap<String, String>()
    while (true) {
        val line = readLine(input) ?: break
        if (line.isEmpty()) break
        val idx = line.indexOf(':')
        if (idx <= 0) continue
        headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
    }
    val length = headers["content-length"]?.toIntOrNull() ?: 0
    if (length > maxBodyBytes) throw HttpBodyTooLarge()
    val body = if (length > 0) {
        val buf = ByteArray(length)
        readFully(input, buf)
        String(buf, StandardCharsets.UTF_8)
    } else {
        ""
    }
    return HttpRequest(parts[0], parts[1], headers, body)
}

/** 读一行（到 `\n`），去掉行尾 `\r`；按字节读，绝不越过正文。 */
private fun readLine(input: InputStream): String? {
    val sb = StringBuilder()
    var any = false
    while (true) {
        val b = input.read()
        if (b == -1) return if (any) sb.toString().trimEnd('\r') else null
        any = true
        if (b == '\n'.code) return sb.toString().trimEnd('\r')
        sb.append(b.toChar())
    }
}

private fun readFully(input: InputStream, buf: ByteArray) {
    var off = 0
    while (off < buf.size) {
        val n = input.read(buf, off, buf.size - off)
        if (n <= 0) break
        off += n
    }
}

/** 写一条响应；统一 `Connection: close`（一次连接一次请求，MCP 调用频率极低）。 */
internal fun writeHttpResponse(
    out: OutputStream,
    status: Int,
    body: String,
    contentType: String? = "application/json; charset=utf-8",
    headers: Map<String, String> = emptyMap()
) {
    val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
    val header = buildString {
        append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
        if (contentType != null && bodyBytes.isNotEmpty()) {
            append("Content-Type: ").append(contentType).append("\r\n")
        }
        headers.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
        append("Content-Length: ").append(bodyBytes.size).append("\r\n")
        append("Connection: close\r\n")
        append("\r\n")
    }
    out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
    if (bodyBytes.isNotEmpty()) out.write(bodyBytes)
    out.flush()
}

private fun reason(status: Int): String = when (status) {
    200 -> "OK"
    202 -> "Accepted"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    403 -> "Forbidden"
    404 -> "Not Found"
    405 -> "Method Not Allowed"
    413 -> "Payload Too Large"
    429 -> "Too Many Requests"
    else -> "OK"
}

/**
 * 只接受私网/回环/链路本地对端。
 * 服务必须绑 `0.0.0.0` 才能被同一局域网的对端访问，这条判定是随之而来的收口：
 * 公网地址（含运营商 NAT 侧、IPv6 全球单播）一律拒掉。
 */
internal fun isPrivatePeer(address: InetAddress?): Boolean {
    if (address == null) return false
    return address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress
}
