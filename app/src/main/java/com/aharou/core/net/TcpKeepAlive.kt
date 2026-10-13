package com.aharou.core.net

import android.system.Os
import android.system.OsConstants
import com.aharou.core.util.FileLogger
import java.io.FileDescriptor
import java.lang.reflect.Method
import java.net.Socket

private const val TAG = "TcpKeepAlive"

/** TCP_KEEPIDLE/TCP_KEEPINTVL/TCP_KEEPCNT 不是 [OsConstants] 暴露的常量，按 Linux 定义取数值。 */
private const val TCP_KEEPIDLE = 4
private const val TCP_KEEPINTVL = 5
private const val TCP_KEEPCNT = 6

/** 首次探测前的空闲时间（秒），小于常见的 NAT 老化时间。 */
private const val IDLE_SECONDS = 45

/** 相邻两次探测的间隔（秒）。 */
private const val INTERVAL_SECONDS = 15

/** 判定连接断开前的未响应探测次数。 */
private const val COUNT = 3

/** Android 上从 Socket 取底层 fd 的隐藏方法，取不到则无法调优间隔。 */
private val fileDescriptorMethod: Method? by lazy {
    runCatching {
        Socket::class.java.getDeclaredMethod("getFileDescriptor$").apply { isAccessible = true }
    }.getOrNull()
}

/**
 * 为 TCP socket 开启 keepalive 并尽量缩短探测间隔。
 *
 * Android 默认 SO_KEEPALIVE 的空闲阈值是系统值（通常 2 小时），远大于移动网络 NAT/路由器
 * 的空闲回收时间，长连接在后台空闲时会被悄悄回收而两端都不知情。这里把空闲阈值压到秒级，
 * 让内核在链路空闲时自动发送探测包刷新 NAT 映射；个别厂商内核不接受这些选项时静默降级为
 * 「仅开启 SO_KEEPALIVE」。
 */
fun Socket.applyTcpKeepAlive() {
    runCatching { keepAlive = true }
        .onFailure { FileLogger.w(TAG, "开启 SO_KEEPALIVE 失败", it) }

    val method = fileDescriptorMethod ?: return
    runCatching {
        val fd = method.invoke(this) as FileDescriptor
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPIDLE, IDLE_SECONDS)
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPINTVL, INTERVAL_SECONDS)
        Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPCNT, COUNT)
    }.onFailure { FileLogger.w(TAG, "调优 TCP keepalive 间隔失败", it) }
}
