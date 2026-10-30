package com.aharou.core.net

/**
 * 进程级网络可用性镜像。
 *
 * [NetworkConnectivityObserver] 在系统网络回调中更新它；不持有 Context 的纯 domain 层
 * （如重试策略）可直接读取，无需注入。默认视为可用，注册回调前不会误判为离线。
 */
object NetworkStatus {
    @Volatile
    var isOnline: Boolean = true
        private set

    fun update(online: Boolean) {
        isOnline = online
    }
}
