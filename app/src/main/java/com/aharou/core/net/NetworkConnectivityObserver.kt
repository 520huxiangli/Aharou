package com.aharou.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局默认网络的可用性监听。
 *
 * 网络恢复或切换到另一条链路（Wi-Fi ⇄ 移动网络）时推送 [recovered]，供 SSH、同步与 Agent 即时重连：
 * 断线恢复若只靠各处轮询，后台状态下窗口可达数十秒。切换网络同样需要重连——旧 TCP 通道的
 * NAT 映射已失效，继续使用只会表现为「连不上」。
 */
@Singleton
class NetworkConnectivityObserver @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val _isOnline = MutableStateFlow(currentlyOnline()).also { NetworkStatus.update(it.value) }

    /** 当前默认网络是否具备互联网能力。 */
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _recovered = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** 网络可用性发生变化（恢复 / 切换链路）时发射，消费方据此立即重建连接。 */
    val recovered: SharedFlow<Unit> = _recovered.asSharedFlow()

    /** 上一次已知的默认网络，用于区分「同一网络重复回调」与「真的换网/恢复」。 */
    private var lastNetwork: Network? = null

    init {
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val changed = network != lastNetwork
                    lastNetwork = network
                    _isOnline.value = true
                    NetworkStatus.update(true)
                    if (changed) {
                        FileLogger.i(TAG, "默认网络可用，触发重连")
                        _recovered.tryEmit(Unit)
                    }
                }

                override fun onLost(network: Network) {
                    _isOnline.value = currentlyOnline()
                    NetworkStatus.update(_isOnline.value)
                    FileLogger.w(TAG, "默认网络断开，isOnline=${_isOnline.value}")
                }
            })
        }.onFailure { FileLogger.e(TAG, "注册网络回调失败", it) }
    }

    private fun currentlyOnline(): Boolean {
        val caps = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
        return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private companion object {
        const val TAG = "NetworkConnectivity"
    }
}
