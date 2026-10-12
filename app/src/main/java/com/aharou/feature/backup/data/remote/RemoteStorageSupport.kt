package com.aharou.feature.backup.data.remote

import com.aharou.feature.backup.domain.remote.RemoteStorageErrorKind
import com.aharou.feature.backup.domain.remote.RemoteStorageException
import java.io.IOException
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/**
 * 网络备份存储共用的文本解析与错误映射工具。
 *
 * XML 走 jsoup 的 XML 解析器（纯 JVM，已在依赖里），按「本地名」匹配标签，兼容不同命名空间前缀
 * （WebDAV 的 `D:response` / `d:response`，S3 的无前缀 `Contents`）。
 */
internal fun parseXml(xml: String): Element = Jsoup.parse(xml, "", Parser.xmlParser())

private fun Element.localName(): String = tagName().substringAfterLast(':')

internal fun Element.descendantsByLocalName(name: String): List<Element> =
    allElements.filter { it !== this && it.localName().equals(name, ignoreCase = true) }

internal fun Element.firstDescendantByLocalName(name: String): Element? =
    descendantsByLocalName(name).firstOrNull()

/** 把底层异常归一成 [RemoteStorageException]，便于上层按 kind 映射文案。 */
internal fun mapRemoteError(e: Throwable): RemoteStorageException = when (e) {
    is RemoteStorageException -> e
    is IOException -> RemoteStorageException(
        RemoteStorageErrorKind.NETWORK,
        e.message ?: e::class.simpleName ?: "network error",
        e
    )

    else -> RemoteStorageException(
        RemoteStorageErrorKind.UNKNOWN,
        e.message ?: e::class.simpleName ?: "unknown error",
        e
    )
}

/** 非 2xx 响应归一成携带状态码分类的异常。 */
internal fun httpError(response: Response): RemoteStorageException {
    val kind = when (response.code) {
        401, 403 -> RemoteStorageErrorKind.AUTH
        404 -> RemoteStorageErrorKind.NOT_FOUND
        else -> RemoteStorageErrorKind.HTTP
    }
    return RemoteStorageException(kind, "HTTP ${response.code} ${response.message}")
}

internal class RemoteConfigException(message: String) :
    RemoteStorageException(RemoteStorageErrorKind.CONFIG, message)
