package com.aharou.feature.backup.data.remote

import com.aharou.feature.backup.domain.remote.BackupRemoteStorage
import com.aharou.feature.backup.domain.remote.BackupTarget
import com.aharou.feature.backup.domain.remote.RemoteBackup
import com.aharou.feature.backup.domain.remote.RemoteStorageErrorKind
import com.aharou.feature.backup.domain.remote.RemoteStorageException
import java.io.File
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * WebDAV 备份后端（Basic 认证）。
 *
 * 列表用 `PROPFIND Depth:1` 解析 207 Multi-Status；连通性探测用 `PROPFIND Depth:0`。
 * 时间取 `getlastmodified`（RFC 1123），大小取 `getcontentlength`。
 */
class WebDavBackupStorage(
    private val config: BackupTarget,
    private val client: OkHttpClient
) : BackupRemoteStorage {

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runRemote { propfind(depth = "0"); Unit }
    }

    override suspend fun list(): Result<List<RemoteBackup>> = withContext(Dispatchers.IO) {
        runRemote {
            parseMultiStatus(propfind(depth = "1"))
                .filterNot { it.isCollection }
                .mapNotNull { entry ->
                    val name = entry.name
                    if (name.isEmpty()) null
                    else RemoteBackup(name = name, size = entry.size, lastModified = entry.lastModified)
                }
        }
    }

    override suspend fun upload(name: String, source: File): Result<Unit> = withContext(Dispatchers.IO) {
        runRemote {
            val request = Request.Builder()
                .url(fileUrl(name))
                .header("Authorization", basicAuth())
                .put(source.asOctetStream())
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw httpError(response)
            }
        }
    }

    override suspend fun download(name: String, target: File): Result<Unit> = withContext(Dispatchers.IO) {
        runRemote {
            val request = Request.Builder()
                .url(fileUrl(name))
                .header("Authorization", basicAuth())
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw httpError(response)
                val stream = response.body?.byteStream()
                    ?: throw RemoteStorageException(RemoteStorageErrorKind.NETWORK, "empty response body")
                target.outputStream().buffered().use { out -> stream.copyTo(out) }
                Unit
            }
        }
    }

    override suspend fun delete(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runRemote {
            val request = Request.Builder()
                .url(fileUrl(name))
                .header("Authorization", basicAuth())
                .delete()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 404) throw httpError(response)
            }
        }
    }

    // ---- 内部实现 ----

    private data class DavEntry(
        val name: String,
        val isCollection: Boolean,
        val size: Long,
        val lastModified: Long
    )

    private fun propfind(depth: String): String {
        val body = PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(baseUrl())
            .header("Authorization", basicAuth())
            .header("Depth", depth)
            .method("PROPFIND", body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpError(response)
            return response.body?.string().orEmpty()
        }
    }

    private fun parseMultiStatus(xml: String): List<DavEntry> {
        val root = parseXml(xml)
        return root.descendantsByLocalName("response").mapNotNull { response ->
            val href = response.firstDescendantByLocalName("href")?.text()?.trim() ?: return@mapNotNull null
            val prop = response.firstDescendantByLocalName("prop") ?: return@mapNotNull null
            val isCollection = prop.descendantsByLocalName("collection").isNotEmpty()
            val size = prop.firstDescendantByLocalName("getcontentlength")?.text()?.trim()?.toLongOrNull() ?: 0L
            val lastModified = prop.firstDescendantByLocalName("getlastmodified")
                ?.text()?.trim()?.let(::parseRfc1123) ?: 0L
            DavEntry(
                name = fileNameOf(href),
                isCollection = isCollection,
                size = size,
                lastModified = lastModified
            )
        }
    }

    /** 从 href 里取文件名：解码 URL 编码，去掉尾部 `/`，取最后一段。 */
    private fun fileNameOf(href: String): String {
        val decoded = runCatching { URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
        return decoded.trimEnd('/').substringAfterLast('/')
    }

    private fun parseRfc1123(raw: String): Long =
        runCatching { ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .getOrDefault(0L)

    private fun basicAuth(): String = Credentials.basic(config.username, config.password)

    private fun baseUrl(): HttpUrl {
        val raw = config.endpoint.trim()
        if (raw.isEmpty()) throw RemoteConfigException("endpoint is required")
        val normalized = if (raw.endsWith("/")) raw else "$raw/"
        val base = normalized.toHttpUrl()
        val basePath = config.basePath.trim().trim('/')
        return if (basePath.isEmpty()) base else base.newBuilder().addPathSegments("$basePath/").build()
    }

    private fun fileUrl(name: String): HttpUrl = baseUrl().newBuilder().addPathSegment(name).build()

    private fun File.asOctetStream() =
        asRequestBody("application/octet-stream".toMediaType())

    private inline fun <T> runRemote(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: Throwable) {
        Result.failure(mapRemoteError(e))
    }

    companion object {
        private val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:resourcetype/>
                <d:getcontentlength/>
                <d:getlastmodified/>
              </d:prop>
            </d:propfind>
        """.trimIndent()
    }
}
