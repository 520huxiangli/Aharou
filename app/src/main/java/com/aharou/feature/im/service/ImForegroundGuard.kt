package com.aharou.feature.im.service

import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.aharou.core.util.FileLogger

/**
 * IM 前台服务的提升守卫。
 *
 * 系统对每次 startForegroundService() 都记一笔账，唯一能清账的动作是 startForeground()。
 * 失败时把异常吞掉不会撤销这笔账，几秒后照样抛 RemoteServiceException；
 * 而在提升成功之前 stopSelf() 会让这笔账永远无法满足，与不提升同构。
 *
 * 所以这里只做三件事：失败换一条不含自定义资源的兜底通知再试一次；提升成功前不执行停止；
 * 停止请求延后到提升之后自己补做。
 */
internal class ImForegroundGuard(
    private val service: Service,
    private val notificationId: Int,
    private val channelId: String,
    private val tag: String
) {

    @Volatile
    private var promoted = false

    /** 提升前到达的停止请求：-1 表示没有。 */
    @Volatile
    private var pendingStopId = -1

    /**
     * 提升为前台。返回 false 表示两条路都失败，此时调用方应放弃这个服务，
     * 因为系统侧的账已经无法靠本进程清掉。
     */
    fun promote(build: () -> Notification): Boolean {
        if (promoted) return true
        val first = runCatching { startForeground(build()) }
        if (first.isSuccess) {
            onPromoted()
            return true
        }
        FileLogger.e(tag, "启动前台通知失败，改用兜底通知重试", first.exceptionOrNull())
        val second = runCatching { startForeground(fallbackNotification()) }
        if (second.isSuccess) {
            onPromoted()
            return true
        }
        FileLogger.e(tag, "兜底前台通知仍失败，放弃该服务", second.exceptionOrNull())
        return false
    }

    /** 已在前台时刷新通知文案；还没提升就退化成一次提升。 */
    fun refresh(build: () -> Notification) {
        if (!promoted) {
            promote(build)
            return
        }
        runCatching { startForeground(build()) }
            .onFailure { FileLogger.w(tag, "刷新前台通知失败", it) }
    }

    /** 请求停止：提升成功前只记账，等 [onPromoted] 补做。 */
    fun stop(startId: Int) {
        if (!promoted) {
            pendingStopId = startId
            return
        }
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        service.stopSelf(startId)
    }

    private fun onPromoted() {
        promoted = true
        val id = pendingStopId
        if (id >= 0) {
            pendingStopId = -1
            ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
            service.stopSelf(id)
        }
    }

    private fun startForeground(notification: Notification) {
        ServiceCompat.startForeground(
            service,
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    /** 兜底通知：只用系统资源与已建好的渠道，任何自定义图标或文案缺失都不会让它失败。 */
    private fun fallbackNotification(): Notification =
        NotificationCompat.Builder(service, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(tag)
            .setOngoing(true)
            .build()
}
