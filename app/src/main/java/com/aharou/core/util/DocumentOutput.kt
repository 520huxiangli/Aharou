package com.aharou.core.util

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun ContentResolver.openDocumentOutput(uri: Uri): OutputStream? {
    var opened: OutputStream? = null
    return try {
        withContext(Dispatchers.IO) {
            openOutputStream(uri).also { opened = it }
        }
    } catch (e: CancellationException) {
        // IO 已开流但主线程取消接收时，流还没交给导出任务，必须在这里释放。
        runCatching { opened?.close() }
        throw e
    } catch (e: SecurityException) {
        FileLogger.w("DocumentOutput", "文档写入授权已失效", e)
        null
    } catch (e: IOException) {
        FileLogger.w("DocumentOutput", "无法打开文档输出流", e)
        null
    }
}
