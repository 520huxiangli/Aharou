package com.aharou.feature.backup.data.remote

import com.aharou.feature.backup.domain.remote.BackupRemoteStorage
import com.aharou.feature.backup.domain.remote.BackupTarget
import com.aharou.feature.backup.domain.remote.RemoteBackup
import com.aharou.feature.backup.domain.remote.RemoteStorageErrorKind
import com.aharou.feature.backup.domain.remote.RemoteStorageException
import java.io.File
import java.security.MessageDigest
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * S3 对象存储后端（手写 SigV4，不引 AWS SDK）。
 *
 * 上传前先对文件做一次 SHA-256（作为 x-amz-content-sha256，随请求签名），再把文件作为请求体发出；
 * 备份包体积有限，多遍历一遍文件换来实现简单、无需 AWS 的分块签名。
 */
class S3BackupStorage(
    private val config: BackupTarget,
    private val client: OkHttpClient
) : BackupRemoteStorage {

    private val region: String = config.region.trim().ifBlank { DEFAULT_REGION }
    private val bucket: String = config.bucket.trim()
    private val pathStyle: Boolean = config.pathStyle
    private val prefix: String = config.basePath.trim().trim('/').let { if (it.isEmpty()) "" else "$it/" }
    private val contentType: String = "application/octet-stream"

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runRemote { listObjects(maxKeys = 1); Unit }
    }

    override suspend fun upload(name: String, source: File): Result<Unit> = withContext(Dispatchers.IO) {
        runRemote {
            val bodyHash = sha256HexOfFile(source)
            val body = source.asRequestBody(contentType.toMediaType())
            val request = buildSignedRequest(
                method = "PUT",
                key = objectKey(name),
                query = "",
                payloadHash = bodyHash,
                requestContentType = contentType,
                body = body
            )
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw httpError(response)
            }
        }
    }

    override suspend fun list(): Result<List<RemoteBackup>> = withContext(Dispatchers.IO) {
        runRemote {
            listObjects(maxKeys = 1000)
                .map { entry ->
                    RemoteBackup(
                        name = entry.key.removePrefix(prefix),
                        size = entry.size,
                        lastModified = entry.lastModified
                    )
                }
                .filter { it.name.isNotEmpty() && !it.name.endsWith("/") }
        }
    }

    override suspend fun download(name: String, target: File): Result<Unit> = withContext(Dispatchers.IO) {
        runRemote {
            val request = buildSignedRequest(
                method = "GET",
                key = objectKey(name),
                query = "",
                payloadHash = S3SigV4.EMPTY_SHA256,
                requestContentType = null,
                body = null
            )
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw httpError(response)
                val stream = response.body?.byteStream() ?: throw RemoteStorageException(
                    RemoteStorageErrorKind.NETWORK,
                    "empty response body"
                )
                target.outputStream().buffered().use { out -> stream.copyTo(out) }
                Unit
            }
        }
    }

    override suspend fun delete(name: String): Result<Unit> = withContext(Dispatchers.IO) {
        runRemote {
            val request = buildSignedRequest(
                method = "DELETE",
                key = objectKey(name),
                query = "",
                payloadHash = S3SigV4.EMPTY_SHA256,
                requestContentType = null,
                body = null
            )
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw httpError(response)
            }
        }
    }

    // ---- 内部实现 ----

    private data class ObjectEntry(val key: String, val size: Long, val lastModified: Long)

    private fun listObjects(maxKeys: Int): List<ObjectEntry> {
        // Canonical query 必须按 key 升序：list-type < max-keys < prefix（字典序）。
        val query = buildString {
            append("list-type=2")
            append("&max-keys=").append(maxKeys)
            if (prefix.isNotEmpty()) append("&prefix=").append(S3SigV4.uriEncode(prefix, encodeSlash = true))
        }
        val request = buildSignedRequest(
            method = "GET",
            key = "",
            query = query,
            payloadHash = S3SigV4.EMPTY_SHA256,
            requestContentType = null,
            body = null
        )
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpError(response)
            val xml = response.body?.string().orEmpty()
            return parseListResult(xml)
        }
    }

    private fun parseListResult(xml: String): List<ObjectEntry> {
        val root = parseXml(xml)
        return root.descendantsByLocalName("contents").mapNotNull { contents ->
            val key = contents.firstDescendantByLocalName("key")?.text() ?: return@mapNotNull null
            val size = contents.firstDescendantByLocalName("size")?.text()?.trim()?.toLongOrNull() ?: 0L
            val lastModified = contents.firstDescendantByLocalName("lastmodified")
                ?.text()?.trim()?.let(::parseIsoInstant) ?: 0L
            ObjectEntry(key, size, lastModified)
        }
    }

    private fun parseIsoInstant(raw: String): Long =
        runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrDefault(0L)

    private fun objectKey(name: String): String = prefix + name.trimStart('/')

    /** 解析服务地址：留空走 AWS 官方端点，否则用用户填的（自建 MinIO / R2 等）。 */
    private fun baseHttpUrl(): HttpUrl {
        val raw = config.endpoint.trim().trimEnd('/')
        return if (raw.isNotEmpty()) {
            raw.toHttpUrl()
        } else {
            "https://s3.$region.amazonaws.com".toHttpUrl()
        }
    }

    private fun buildSignedRequest(
        method: String,
        key: String,
        query: String,
        payloadHash: String,
        requestContentType: String?,
        body: RequestBody?
    ): Request {
        val base = baseHttpUrl()
        val host = if (pathStyle) base.host else "$bucket.${base.host}"
        val encodedKey = S3SigV4.uriEncode(key, encodeSlash = false)
        val path = when {
            pathStyle && key.isEmpty() -> "/$bucket"
            pathStyle -> "/$bucket/$encodedKey"
            key.isEmpty() -> "/"
            else -> "/$encodedKey"
        }
        val port = if (base.port != HttpUrl.defaultPort(base.scheme)) ":${base.port}" else ""
        val hostHeader = host + port
        val rawUrl = buildString {
            append(base.scheme).append("://").append(host).append(port).append(path)
            if (query.isNotEmpty()) append('?').append(query)
        }
        val url = rawUrl.toHttpUrl()

        val amzDate = S3SigV4.amzDate(System.currentTimeMillis())
        val token = config.sessionToken.trim().takeIf { it.isNotEmpty() }

        val headers = LinkedHashMap<String, String>()
        headers["host"] = hostHeader
        headers["x-amz-content-sha256"] = payloadHash
        headers["x-amz-date"] = amzDate
        token?.let { headers["x-amz-security-token"] = it }
        requestContentType?.let { headers["content-type"] = it }

        val authorization = S3SigV4.sign(
            accessKey = config.accessKey.trim(),
            secretKey = config.secretKey,
            region = region,
            method = method,
            canonicalUri = url.encodedPath,
            canonicalQuery = query,
            headers = headers,
            payloadHash = payloadHash,
            amzDate = amzDate
        )

        val builder = Request.Builder()
            .url(url)
            .header("x-amz-content-sha256", payloadHash)
            .header("x-amz-date", amzDate)
            .header("Authorization", authorization)
        token?.let { builder.header("x-amz-security-token", it) }
        requestContentType?.let { builder.header("Content-Type", it) }
        return builder.method(method, body).build()
    }

    private fun sha256HexOfFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return S3SigV4.hex(digest.digest())
    }

    private inline fun <T> runRemote(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: Throwable) {
        Result.failure(mapRemoteError(e))
    }

    companion object {
        private const val DEFAULT_REGION = "us-east-1"
    }
}
