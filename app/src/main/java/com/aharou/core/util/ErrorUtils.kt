package com.aharou.core.util

import android.content.Context
import com.aharou.R
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 把异常转换成对用户有意义的提示文本。
 *
 * 先按网络错误类型归类（DNS 解析失败 / 连接被拒 / 超时），再另起一行附上系统原文——
 * 只给归类会盖掉服务端返回的具体原因，只给原文则用户看不出是地址错还是网络不通。
 * 归类文案复用重试气泡那套 `retry_error_*`，跟随界面语言切换、两处措辞保持一致。
 *
 * 很多异常（如 NPE、被取消的请求）的 [Throwable.message] 为 null，
 * 直接使用会显示成 "null"。这里回退到异常类名，并向上追溯 cause 链，
 * 尽量给出可定位问题的信息。
 */
fun Throwable.toUserMessage(context: Context): String {
    val detail = detailText()
    val categoryRes = networkCategoryRes() ?: return detail
    return context.getString(categoryRes) + "\n" + detail
}

/** 沿 cause 链识别网络错误类型（OkHttp 常把根因包在 cause 里）；识别不出返回 null，即「其它」。 */
internal fun Throwable.networkCategoryRes(): Int? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 4) {
        when (current) {
            is UnknownHostException -> return R.string.retry_error_dns_failed
            is ConnectException -> return R.string.retry_error_connection_refused
            is SocketTimeoutException, is InterruptedIOException -> return R.string.retry_error_timeout
        }
        current = current.cause
        depth++
    }
    return null
}

/** 拼接 cause 链上各层的可读文本（去重、最多 4 层，message 为空时回退类名）。 */
internal fun Throwable.detailText(): String {
    val parts = mutableListOf<String>()
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 4) {
        val msg = current.message?.trim().orEmpty()
        val label = if (msg.isNotEmpty()) msg else current.javaClass.simpleName
        if (parts.none { it == label }) parts.add(label)
        current = current.cause
        depth++
    }
    return parts.joinToString(" · ")
}
