package com.aharou.feature.settings.data.remote

import android.content.Context
import android.net.Uri
import com.aharou.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

class ContainerImageChecksumException(message: String) : IOException(message)

@Singleton
class ContainerImageDownloader @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val client by lazy {
        OkHttpClient.Builder()
            .proxyAuthenticator(com.aharou.core.net.AppProxy.okHttpAuthenticator)
            .build()
    }

    suspend fun download(
        url: String,
        fileName: String,
        expectedSha256: String? = null,
        connectTimeoutSeconds: Long = 10,
        readTimeoutSeconds: Long = 10,
        writeTimeoutSeconds: Long = 10,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "rootfs_images").apply { mkdirs() }
        val dest = File(dir, fileName)
        val temporary = File(dir, ".${fileName}.${UUID.randomUUID()}.part")
        val downloadClient = client.newBuilder()
            .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
            .build()
        val call = downloadClient.newCall(Request.Builder().url(url).build())
        val finished = CompletableDeferred<Unit>()
        try {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        try {
                            temporary.delete()
                            if (cont.isActive) cont.resumeWithException(e)
                        } finally {
                            finished.complete(Unit)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                if (!cont.isActive) return
                                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                                val body = response.body ?: throw IOException("Empty response body")
                                val total = body.contentLength()
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var read = 0L
                                val digest = MessageDigest.getInstance("SHA-256")
                                body.byteStream().use { input ->
                                    temporary.outputStream().use { output ->
                                        while (true) {
                                            if (!cont.isActive) return
                                            val n = input.read(buffer)
                                            if (n < 0) break
                                            if (!cont.isActive) return
                                            output.write(buffer, 0, n)
                                            digest.update(buffer, 0, n)
                                            read += n
                                            onProgress(read, total)
                                        }
                                    }
                                }
                                if (!cont.isActive) return
                                if (!expectedSha256.isNullOrBlank()) {
                                    val actual = digest.digest().joinToString("") { "%02x".format(it) }
                                    if (!actual.equals(expectedSha256, ignoreCase = true)) {
                                        throw ContainerImageChecksumException(context.getString(R.string.container_download_checksum_failed))
                                    }
                                }
                                if (!temporary.renameTo(dest)) throw IOException("Cannot commit downloaded file")
                                cont.resume(Uri.fromFile(dest).toString(), onCancellation = { _, _, _ -> dest.delete() })
                            }
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resumeWithException(e)
                        } finally {
                            temporary.delete()
                            finished.complete(Unit)
                        }
                    }
                })
            }
        } catch (e: CancellationException) {
            // 等回调关闭文件流后再交还调用方，防止换源时上一次写入仍未结束。
            withContext(NonCancellable) { finished.await() }
            throw e
        }
    }
}
