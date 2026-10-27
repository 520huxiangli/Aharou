package com.aharou.feature.settings.data.localmodels

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.Inet4Address
import java.net.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * 局域网 OpenAI 兼容推理服务发现（llama.cpp / Ollama / LM Studio / vLLM 等）。
 *
 * 两条线索合并：
 * 1. [NsdManager] 扫 `_http._tcp.` 服务，拿到设备名与端口；
 * 2. 对 NSD 发现的设备 IP 与本机网关 IP，逐个端口 GET `/v1/models`，用响应确认是不是推理服务。
 *
 * 整体限时 [DEFAULT_TOTAL_TIMEOUT_MS]，NSD 阶段单独限时 [NSD_SCAN_TIMEOUT_MS]；
 * 取消时注销 NSD 监听并 cancel 正在进行中的 OkHttp 请求。
 */
@Singleton
class LocalModelDiscovery @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    /** 发现渠道：NSD 广播（带设备名）或端口探测（按端口猜厂商）。 */
    enum class Source { NSD, PORT_SCAN }

    data class LocalModelService(
        val displayName: String,
        val baseUrl: String,
        val source: Source
    )

    private data class ProbeTarget(val ip: String, val port: Int)

    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            // 局域网直连：别让用户配置的系统代理把 192.168.x.x 的请求转发出局。
            .proxy(Proxy.NO_PROXY)
            .build()
    }

    /**
     * 扫描局域网。整体超时不抛异常，返回已经收集到的部分结果；外部取消则抛 [CancellationException]。
     */
    suspend fun discover(totalTimeoutMs: Long = DEFAULT_TOTAL_TIMEOUT_MS): List<LocalModelService> =
        withContext(Dispatchers.IO) {
            val nsdServices = CopyOnWriteArrayList<LocalModelService>()
            val nsdHosts = CopyOnWriteArrayList<String>()
            val merged = LinkedHashMap<String, LocalModelService>()

            val completed = withTimeoutOrNull(totalTimeoutMs) {
                coroutineScope {
                    val nsdJob = launch { scanViaNsd(nsdServices, nsdHosts) }
                    withTimeoutOrNull(NSD_SCAN_TIMEOUT_MS) { nsdJob.join() }
                    // 到点收手：注销监听（在 scanViaNsd 的 finally 里），未完成的解析一并取消。
                    nsdJob.cancelAndJoin()
                }
                // NSD 只用来找主机与端口：局域网里摄像头、路由器都广播 _http._tcp，
                // 必须再用 /v1/models 校验确实是推理服务，不能直接当结果显示。
                val nsdTargets = nsdServices.mapNotNull { service -> parseTarget(service.baseUrl) }
                probeAll(nsdTargets).forEach { merged.putIfAbsent(it.baseUrl, it) }

                val ips = LinkedHashSet<String>()
                ips.addAll(nsdHosts)
                ips.addAll(gatewayIps())
                val targets = ips
                    .filter { IPV4_REGEX.matches(it) }
                    .flatMap { ip -> PROBE_PORTS.map { port -> ProbeTarget(ip, port) } }
                probeAll(targets).forEach { merged.putIfAbsent(it.baseUrl, it) }
                true
            }
            if (completed == null) {
                // 总超时：NSD 阶段的结果已经并入 merged，端口探测没跑完的部分直接放弃。
                FileLogger.d(TAG, "扫描总超时，返回 ${merged.size} 个已发现服务")
            }

            merged.values.toList()
        }

    // ---------- NSD ----------

    private suspend fun scanViaNsd(
        out: MutableList<LocalModelService>,
        hosts: MutableList<String>
    ) {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType?.contains(HTTP_SERVICE_LABEL) != true) return
                queue.trySend(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                FileLogger.w(TAG, "NSD 启动失败 errorCode=$errorCode")
                runCatching { nsdManager.stopServiceDiscovery(this) }
                queue.close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                FileLogger.w(TAG, "NSD 停止失败 errorCode=$errorCode")
            }
        }
        try {
            nsdManager.discoverServices(HTTP_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            // resolveService 同一时刻只允许一个在跑，这里串行消费。
            for (info in queue) {
                val resolved = resolveService(nsdManager, info) ?: continue
                @Suppress("DEPRECATION")
                val address = resolved.host?.hostAddress ?: continue
                val port = resolved.port
                if (port <= 0) continue
                hosts += address
                out += LocalModelService(
                    displayName = resolved.serviceName?.takeIf { it.isNotBlank() } ?: address,
                    baseUrl = baseUrl(address, port),
                    source = Source.NSD
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "NSD 发现异常", e)
        } finally {
            queue.close()
            runCatching { nsdManager.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun resolveService(
        nsdManager: NsdManager,
        info: NsdServiceInfo
    ): NsdServiceInfo? = suspendCancellableCoroutine { cont ->
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (cont.isActive) cont.resume(null)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                if (cont.isActive) cont.resume(serviceInfo)
            }
        }
        try {
            nsdManager.resolveService(info, listener)
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    // ---------- 端口探测 ----------

    private suspend fun probeAll(targets: List<ProbeTarget>): List<LocalModelService> =
        coroutineScope {
            targets.map { target ->
                async { probe(target) }
            }.awaitAll().filterNotNull()
        }

    private suspend fun probe(target: ProbeTarget): LocalModelService? {
        val base = baseUrl(target.ip, target.port)
        if (!answersModelsEndpoint("$base/v1/models")) return null
        return LocalModelService(
            displayName = displayNameForPort(target.port),
            baseUrl = base,
            source = Source.PORT_SCAN
        )
    }

    private suspend fun answersModelsEndpoint(url: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val request = try {
                Request.Builder().url(url).get().build()
            } catch (e: IllegalArgumentException) {
                cont.resume(false)
                return@suspendCancellableCoroutine
            }
            val call = probeClient.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(false)
                }

                override fun onResponse(call: Call, response: Response) {
                    val compatible = response.use { resp ->
                        resp.isSuccessful && resp.body?.string().orEmpty().contains(MODELS_MARKER)
                    }
                    if (cont.isActive) cont.resume(compatible)
                }
            })
        }

    /** 网关：优先取活动网络的默认路由（Wi-Fi、热点、USB 共享都适用），取不到再退回 DHCP。 */
    private fun gatewayIps(): List<String> {
        val result = LinkedHashSet<String>()
        runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = manager?.activeNetwork
            val onWifi = manager?.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            // 只在 Wi-Fi 上取默认网关：移动数据的网关是运营商地址，扫它没有意义。
            if (onWifi) {
                val props = network?.let { manager.getLinkProperties(it) }
                props?.routes?.forEach { route ->
                    if (!route.isDefaultRoute) return@forEach
                    val gateway = route.gateway
                    if (gateway is Inet4Address) gateway.hostAddress?.let { result += it }
                }
            }
        }
        if (result.isEmpty()) {
            runCatching {
                @Suppress("DEPRECATION")
                val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val gateway = wifi?.dhcpInfo?.gateway ?: 0
                if (gateway != 0) result += ipv4FromInt(gateway)
            }
        }
        return result.toList()
    }

    private fun ipv4FromInt(value: Int): String =
        "${value and 0xFF}.${(value shr 8) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 24) and 0xFF}"

    private fun baseUrl(ip: String, port: Int): String = "http://$ip:$port"

    /** 把 NSD 解析出来的 baseUrl 拆回主机与端口，交给同一个 /v1/models 校验。 */
    private fun parseTarget(baseUrl: String): ProbeTarget? {
        val withoutScheme = baseUrl.removePrefix("http://").removePrefix("https://")
        val host = withoutScheme.substringBefore(':', "").takeIf { it.isNotBlank() } ?: return null
        val port = withoutScheme.substringAfter(':', "").substringBefore('/').toIntOrNull() ?: return null
        return ProbeTarget(host, port)
    }

    private fun displayNameForPort(port: Int): String = when (port) {
        OLLAMA_PORT -> "Ollama"
        LM_STUDIO_PORT -> "LM Studio"
        else -> "OpenAI-compatible (:$port)"
    }

    companion object {
        private const val TAG = "LocalModelDiscovery"

        private const val HTTP_SERVICE_TYPE = "_http._tcp."
        private const val HTTP_SERVICE_LABEL = "_http"

        private const val OLLAMA_PORT = 11434
        private const val LM_STUDIO_PORT = 1234

        /** 常见 OpenAI 兼容服务端口：Ollama / LM Studio / 通用 HTTP 端口。 */
        private val PROBE_PORTS = listOf(OLLAMA_PORT, LM_STUDIO_PORT, 8080, 8000, 5000)

        private const val PROBE_TIMEOUT_MS = 1_500L
        private const val NSD_SCAN_TIMEOUT_MS = 5_000L
        private const val DEFAULT_TOTAL_TIMEOUT_MS = 8_000L

        private const val MODELS_MARKER = "\"data\""

        private val IPV4_REGEX = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    }
}
