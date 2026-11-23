package com.aharou.feature.plugin.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 经典蓝牙（RFCOMM）通道。
 *
 * 移植自 Operit（LGPL-3.0）`BluetoothSessionManager` 的经典蓝牙部分：会话注册表、
 * 发现、连接/监听/accept、读写与关闭。
 *
 * 上游把 socket 直接放进 map，没有超时回收与断线通知，插件漏调 close 就会一直攥着句柄。
 * 这里补三件事：
 * - 会话空闲 30 分钟由后台回收器释放（socket 与 serverSocket 都不会自己超时）；
 * - 写失败或读到 EOF 视为对端断开，立即释放并通过事件告知调用方；
 * - 会话总数封顶，超出返回 BUSY，避免插件循环连接把 fd 耗光。
 */
internal class BluetoothClassicChannel(
    private val context: Context,
    private val events: (BluetoothSessionClosedEvent) -> Unit
) {

    private class ClassicSession(
        val id: String,
        val address: String,
        val socket: BluetoothSocket,
        val input: InputStream,
        val output: OutputStream
    ) {
        @Volatile
        var lastUsedAt: Long = System.currentTimeMillis()

        @Volatile
        var closed: Boolean = false
    }

    private class ClassicListener(
        val id: String,
        val serverSocket: BluetoothServerSocket,
        val name: String,
        val uuid: UUID
    ) {
        @Volatile
        var lastUsedAt: Long = System.currentTimeMillis()

        @Volatile
        var closed: Boolean = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, ClassicSession>()
    private val listeners = ConcurrentHashMap<String, ClassicListener>()

    private var reaper: Job? = null

    /** 经典发现；结果合并进 [out]，同一设备被 BLE 通道也报上来时由 [mergeScannedDevice] 合并。 */
    @SuppressLint("MissingPermission")
    internal suspend fun scanDevices(
        adapter: BluetoothAdapter,
        durationMs: Long,
        out: MutableMap<String, BluetoothScannedDevice>
    ) = withContext(Dispatchers.Main) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_FOUND) return
                val device = intent.bluetoothDevice() ?: return
                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                mergeScannedDevice(
                    out,
                    BluetoothScannedDevice(
                        name = runCatching { device.name }.getOrNull(),
                        address = device.address,
                        type = deviceTypeName(device.type),
                        bondState = bondStateName(device.bondState),
                        source = "classic",
                        rssi = if (rssi == Short.MIN_VALUE) null else rssi.toInt()
                    )
                )
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            runCatching { adapter.cancelDiscovery() }
            if (!adapter.startDiscovery()) {
                throw IOException("startDiscovery 被系统拒绝")
            }
            delay(durationMs)
        } finally {
            runCatching { adapter.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(
        adapter: BluetoothAdapter,
        address: String,
        uuid: UUID,
        timeoutMs: Long
    ): BtResult<BluetoothSessionInfo> {
        if (isFull()) return busyResult()
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return BtResult.Failure(BtErrorCode.PARAM_INVALID, "蓝牙地址无效: $address")
        }
        // 发现过程会拖慢连接甚至让连接失败，但 cancelDiscovery 在 Android 12+ 还要 SCAN 权限；
        // 拿不到就跳过，不能因为没给扫描权限把连接也一起堵死。
        runCatching { adapter.cancelDiscovery() }
        val socket = try {
            device.createRfcommSocketToServiceRecord(uuid)
        } catch (e: IOException) {
            return BtResult.Failure(BtErrorCode.CONNECT_FAILED, "创建 RFCOMM socket 失败: ${e.message}")
        }
        return try {
            when (val outcome = connectSocket(socket, timeoutMs)) {
                ConnectOutcome.Ok -> {
                    val session = ClassicSession(
                        id = newSessionId("bt"),
                        address = address,
                        socket = socket,
                        input = socket.inputStream,
                        output = socket.outputStream
                    )
                    sessions[session.id] = session
                    ensureReaper()
                    BtResult.Success(BluetoothSessionInfo(session.id, address, "classic"))
                }

                ConnectOutcome.Timeout -> {
                    closeQuietly(socket)
                    BtResult.Failure(BtErrorCode.TIMEOUT, "连接超时（${timeoutMs}ms）: $address")
                }

                is ConnectOutcome.Failed -> {
                    closeQuietly(socket)
                    BtResult.Failure(
                        BtErrorCode.CONNECT_FAILED,
                        "连接失败: ${outcome.error?.message ?: "unknown"}"
                    )
                }
            }
        } catch (e: CancellationException) {
            closeQuietly(socket)
            throw e
        } catch (e: IOException) {
            closeQuietly(socket)
            BtResult.Failure(BtErrorCode.IO_ERROR, "打开数据流失败: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun listen(
        adapter: BluetoothAdapter,
        name: String,
        uuid: UUID
    ): BtResult<BluetoothSessionInfo> {
        if (isFull()) return busyResult()
        return withContext(Dispatchers.IO) {
            try {
                val serverSocket = adapter.listenUsingRfcommWithServiceRecord(name, uuid)
                val listener = ClassicListener(
                    id = newSessionId("bt-listen"),
                    serverSocket = serverSocket,
                    name = name,
                    uuid = uuid
                )
                listeners[listener.id] = listener
                ensureReaper()
                BtResult.Success(BluetoothSessionInfo(listener.id, "local", "classic_listener"))
            } catch (e: IOException) {
                BtResult.Failure(BtErrorCode.IO_ERROR, "监听失败: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun accept(listenerId: String, timeoutMs: Long): BtResult<BluetoothSessionInfo> {
        val listener = listeners[listenerId] ?: return btNotFound(listenerId)
        if (isFull()) return busyResult()
        return withContext(Dispatchers.IO) {
            val effectiveTimeout = timeoutMs.coerceIn(1_000L, Int.MAX_VALUE.toLong()).toInt()
            val startedAt = System.currentTimeMillis()
            try {
                listener.lastUsedAt = System.currentTimeMillis()
                // accept(timeout) 超时返回 null，不会抛异常，正好区分「超时」与「被关闭」。
                val socket = listener.serverSocket.accept(effectiveTimeout)
                    ?: return@withContext BtResult.Failure(
                        BtErrorCode.TIMEOUT,
                        "等待连接超时（${effectiveTimeout}ms）"
                    )
                val session = try {
                    ClassicSession(
                        id = newSessionId("bt"),
                        address = socket.remoteDevice.address,
                        socket = socket,
                        input = socket.inputStream,
                        output = socket.outputStream
                    )
                } catch (e: Exception) {
                    closeQuietly(socket)
                    throw e
                }
                sessions[session.id] = session
                listener.lastUsedAt = System.currentTimeMillis()
                ensureReaper()
                BtResult.Success(BluetoothSessionInfo(session.id, session.address, "classic"))
            } catch (e: IOException) {
                // 被 close() 打断的 accept 也会抛 IOException：先看监听器还在不在，再判超时。
                if (listeners[listenerId] !== listener) {
                    btNotFound(listenerId)
                } else if (System.currentTimeMillis() - startedAt >= effectiveTimeout - 50L) {
                    BtResult.Failure(BtErrorCode.TIMEOUT, "等待连接超时（${effectiveTimeout}ms）")
                } else {
                    BtResult.Failure(BtErrorCode.IO_ERROR, "accept 失败: ${e.message}")
                }
            }
        }
    }

    suspend fun send(sessionId: String, payload: ByteArray): BtResult<BluetoothTransfer> =
        withContext(Dispatchers.IO) {
            val session = sessions[sessionId] ?: return@withContext btNotFound(sessionId)
            if (session.closed) return@withContext btNotFound(sessionId)
            try {
                session.output.write(payload)
                session.output.flush()
                session.lastUsedAt = System.currentTimeMillis()
                BtResult.Success(BluetoothTransfer(sessionId, payload.size))
            } catch (e: IOException) {
                // 写失败基本等于对端已断开：立刻释放，避免半死会话继续占着句柄。
                dropSession(session, "io_error")
                BtResult.Failure(BtErrorCode.IO_ERROR, "写入失败: ${e.message}")
            }
        }

    suspend fun read(sessionId: String, maxBytes: Int, timeoutMs: Long): BtResult<BluetoothReadResult> =
        withContext(Dispatchers.IO) {
            val session = sessions[sessionId] ?: return@withContext btNotFound(sessionId)
            val size = maxBytes.coerceIn(1, MAX_READ_BYTES)
            val buffer = ByteArray(size)
            val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
            var count = 0
            var eof = false
            try {
                // InputStream.available() 不阻塞，用轮询换取可取消的超时；Operit 上游同样这么做。
                while (count == 0 && !eof && System.currentTimeMillis() < deadline) {
                    if (session.closed) return@withContext btNotFound(sessionId)
                    val available = session.input.available()
                    if (available > 0) {
                        val readBytes = session.input.read(buffer, 0, minOf(size, available))
                        if (readBytes < 0) eof = true else count = readBytes
                    } else {
                        delay(READ_POLL_MS)
                    }
                }
            } catch (e: IOException) {
                dropSession(session, "io_error")
                return@withContext BtResult.Failure(BtErrorCode.IO_ERROR, "读取失败: ${e.message}")
            }
            if (eof) {
                dropSession(session, "peer_closed")
                return@withContext BtResult.Failure(BtErrorCode.IO_ERROR, "对端已关闭连接")
            }
            session.lastUsedAt = System.currentTimeMillis()
            val bytes = if (count > 0) buffer.copyOf(count) else ByteArray(0)
            BtResult.Success(
                BluetoothReadResult(
                    sessionId = sessionId,
                    bytesRead = bytes.size,
                    text = bytes.utf8OrNull(),
                    dataBase64 = bytes.takeIf { it.isNotEmpty() }?.encodeBase64()
                )
            )
        }

    suspend fun sendAndRead(
        sessionId: String,
        payload: ByteArray,
        maxBytes: Int,
        timeoutMs: Long
    ): BtResult<BluetoothReadResult> {
        return when (val sent = send(sessionId, payload)) {
            is BtResult.Failure -> sent
            is BtResult.Success -> read(sessionId, maxBytes, timeoutMs)
        }
    }

    suspend fun close(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val session = sessions.remove(sessionId)
        if (session != null) {
            closeSession(session, "closed")
            return@withContext true
        }
        val listener = listeners.remove(sessionId)
        if (listener != null) {
            closeListener(listener, "closed")
            return@withContext true
        }
        false
    }

    private suspend fun connectSocket(socket: BluetoothSocket, timeoutMs: Long): ConnectOutcome {
        val done = CompletableDeferred<Boolean>()
        var connectError: Throwable? = null
        // 在通道自己的 scope 里跑阻塞 connect：withTimeout 直接包它会因为阻塞调用不可取消而挂住。
        val job = scope.launch {
            connectError = runCatching { socket.connect() }.exceptionOrNull()
            done.complete(true)
        }
        val finished = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { done.await() } ?: false
        if (!finished) {
            // 关掉 socket 能把阻塞中的 connect 顶出来，再取消等待协程。
            closeQuietly(socket)
            job.cancel()
            return ConnectOutcome.Timeout
        }
        val error = connectError
        return if (error == null) ConnectOutcome.Ok else ConnectOutcome.Failed(error)
    }

    private fun dropSession(session: ClassicSession, reason: String) {
        synchronized(session) {
            if (session.closed) return
            session.closed = true
        }
        sessions.remove(session.id, session)
        closeQuietly(session.socket)
        events(BluetoothSessionClosedEvent(session.id, "classic", session.address, reason))
    }

    private fun closeSession(session: ClassicSession, reason: String) = dropSession(session, reason)

    private fun closeListener(listener: ClassicListener, reason: String) {
        synchronized(listener) {
            if (listener.closed) return
            listener.closed = true
        }
        listeners.remove(listener.id, listener)
        runCatching { listener.serverSocket.close() }
        events(BluetoothSessionClosedEvent(listener.id, "classic_listener", "local", reason))
    }

    @Synchronized
    private fun ensureReaper() {
        if (reaper?.isActive == true) return
        reaper = scope.launch {
            while (true) {
                delay(BluetoothConstants.REAPER_INTERVAL_MS)
                reapIdle()
            }
        }
    }

    private fun reapIdle() {
        val deadline = System.currentTimeMillis() - BluetoothConstants.SESSION_IDLE_TIMEOUT_MS
        sessions.values.filter { it.lastUsedAt < deadline }.forEach { dropSession(it, "idle_timeout") }
        listeners.values.filter { it.lastUsedAt < deadline }.forEach { closeListener(it, "idle_timeout") }
    }

    private fun isFull(): Boolean = sessions.size + listeners.size >= BluetoothConstants.MAX_SESSIONS

    private fun busyResult(): BtResult.Failure = BtResult.Failure(
        BtErrorCode.BUSY,
        "蓝牙会话数已达上限（${BluetoothConstants.MAX_SESSIONS}），请先关闭不用的会话"
    )

    private fun newSessionId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    private fun closeQuietly(socket: BluetoothSocket) {
        runCatching { socket.close() }
    }

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private sealed interface ConnectOutcome {
        data object Ok : ConnectOutcome
        data object Timeout : ConnectOutcome
        data class Failed(val error: Throwable?) : ConnectOutcome
    }

    private companion object {
        const val READ_POLL_MS = 20L
        const val MAX_READ_BYTES = 1024 * 1024
    }
}
