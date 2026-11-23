package com.aharou.feature.plugin.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * BLE（GATT）通道。
 *
 * 移植自 Operit（LGPL-3.0）`BluetoothSessionManager` 的 BLE 部分：连接、服务发现、
 * 特征读写、CCCD 订阅与通知缓冲。
 *
 * 相对上游补齐的点：
 * - 连接会等 `STATE_CONNECTED` 再返回（上游立刻返回，连不上时只留一个悬空 gatt）；
 * - 所有 GATT 请求串行化（一个 gatt 同时只能有一个未完成请求，上游只给写加了锁）；
 * - 对端断开时主动清理会话并发出事件，持有 gatt 的插件能感知到；
 * - 通知缓冲有上限，插件长时间不读不会无限吃内存。
 */
internal class BluetoothBleChannel(
    private val context: Context,
    private val events: (BluetoothSessionClosedEvent) -> Unit
) {

    private class BleSession(val id: String, val address: String) {
        @Volatile
        var gatt: BluetoothGatt? = null

        val connected = CompletableDeferred<Boolean>()
        val servicesReady = CompletableDeferred<Int>()

        val notifications = mutableListOf<BleNotificationEntry>()
        val readWaiters = ConcurrentHashMap<String, CompletableDeferred<BleValue>>()
        val writeWaiters = ConcurrentHashMap<String, CompletableDeferred<Int>>()
        val descriptorWaiters = ConcurrentHashMap<String, CompletableDeferred<Int>>()

        /** GATT 同时只允许一个未完成请求，读写与 CCCD 写入都串在这把锁上。 */
        val opMutex = Mutex()

        @Volatile
        var mtu: Int = 23

        @Volatile
        var lastUsedAt: Long = System.currentTimeMillis()

        @Volatile
        var closed: Boolean = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, BleSession>()

    private var reaper: Job? = null

    /** BLE 扫描；与经典发现并行跑，结果合并进同一个 map。 */
    @SuppressLint("MissingPermission")
    internal suspend fun scanDevices(
        adapter: BluetoothAdapter,
        durationMs: Long,
        out: MutableMap<String, BluetoothScannedDevice>
    ) = withContext(Dispatchers.Main) {
        val scanner = adapter.bluetoothLeScanner ?: return@withContext
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                recordScanResult(result, out)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { recordScanResult(it, out) }
            }
        }
        try {
            scanner.startScan(callback)
            delay(durationMs)
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(
        adapter: BluetoothAdapter,
        address: String,
        autoConnect: Boolean,
        timeoutMs: Long
    ): BtResult<BluetoothSessionInfo> {
        if (sessions.size >= BluetoothConstants.MAX_SESSIONS) return busyResult()
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return BtResult.Failure(BtErrorCode.PARAM_INVALID, "蓝牙地址无效: $address")
        }
        val session = BleSession(newSessionId("ble"), address)
        val callback = buildGattCallback(session)
        val gatt = try {
            withContext(Dispatchers.Main) {
                device.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
            }
        } catch (e: SecurityException) {
            return BtResult.Failure(BtErrorCode.PERMISSION_DENIED, e.message ?: "缺少蓝牙连接权限")
        }
        if (gatt == null) {
            return BtResult.Failure(BtErrorCode.CONNECT_FAILED, "BLE 连接未能建立: $address")
        }
        session.gatt = gatt
        sessions[session.id] = session
        ensureReaper()
        return try {
            val connected = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { session.connected.await() }
            when {
                connected == null -> {
                    dropSession(session, "connect_timeout", emit = false)
                    BtResult.Failure(BtErrorCode.TIMEOUT, "BLE 连接超时（${timeoutMs}ms）: $address")
                }

                !connected -> {
                    dropSession(session, "connect_failed", emit = false)
                    BtResult.Failure(BtErrorCode.CONNECT_FAILED, "BLE 连接失败: $address")
                }

                else -> BtResult.Success(BluetoothSessionInfo(session.id, address, "ble"))
            }
        } catch (e: CancellationException) {
            dropSession(session, "cancelled", emit = false)
            throw e
        }
    }

    suspend fun discoverServices(sessionId: String, timeoutMs: Long): BtResult<BleServicesInfo> {
        val session = sessions[sessionId] ?: return btNotFound(sessionId)
        val status = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { session.servicesReady.await() }
            ?: return BtResult.Failure(BtErrorCode.TIMEOUT, "BLE 服务发现超时（${timeoutMs}ms）")
        if (status != BluetoothGatt.GATT_SUCCESS) {
            return BtResult.Failure(BtErrorCode.GATT_ERROR, "BLE 服务发现失败: status=$status")
        }
        val gatt = session.gatt ?: return btNotFound(sessionId)
        session.lastUsedAt = System.currentTimeMillis()
        val services = gatt.services.map { service ->
            BleServiceInfo(
                uuid = service.uuid.toString(),
                characteristics = service.characteristics.map { characteristic ->
                    BleCharacteristicInfo(
                        uuid = characteristic.uuid.toString(),
                        properties = characteristicProperties(characteristic.properties)
                    )
                }
            )
        }
        return BtResult.Success(BleServicesInfo(sessionId, services))
    }

    suspend fun readCharacteristic(
        sessionId: String,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        timeoutMs: Long
    ): BtResult<BluetoothReadResult> {
        val session = sessions[sessionId] ?: return btNotFound(sessionId)
        val gatt = session.gatt ?: return btNotFound(sessionId)
        val characteristic = when (val found = findCharacteristic(gatt, serviceUuid, characteristicUuid)) {
            is BtResult.Failure -> return found
            is BtResult.Success -> found.value
        }
        return session.opMutex.withLock {
            if (session.closed) return@withLock btNotFound(sessionId)
            session.lastUsedAt = System.currentTimeMillis()
            val key = characteristicKey(serviceUuid, characteristicUuid)
            val waiter = CompletableDeferred<BleValue>()
            session.readWaiters[key] = waiter
            try {
                if (!gatt.readCharacteristic(characteristic)) {
                    return@withLock BtResult.Failure(BtErrorCode.IO_ERROR, "启动 BLE 特征读取失败")
                }
                val outcome = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { waiter.await() }
                    ?: return@withLock BtResult.Failure(BtErrorCode.TIMEOUT, "BLE 特征读取超时（${timeoutMs}ms）")
                if (outcome.status != BluetoothGatt.GATT_SUCCESS) {
                    return@withLock BtResult.Failure(BtErrorCode.GATT_ERROR, "BLE 特征读取失败: status=${outcome.status}")
                }
                val bytes = outcome.value ?: ByteArray(0)
                BtResult.Success(
                    BluetoothReadResult(
                        sessionId = sessionId,
                        bytesRead = bytes.size,
                        text = bytes.utf8OrNull(),
                        dataBase64 = bytes.takeIf { it.isNotEmpty() }?.encodeBase64()
                    )
                )
            } finally {
                session.readWaiters.remove(key)
            }
        }
    }

    suspend fun writeCharacteristic(
        sessionId: String,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        payload: ByteArray
    ): BtResult<BluetoothTransfer> {
        val session = sessions[sessionId] ?: return btNotFound(sessionId)
        val gatt = session.gatt ?: return btNotFound(sessionId)
        val characteristic = when (val found = findCharacteristic(gatt, serviceUuid, characteristicUuid)) {
            is BtResult.Failure -> return found
            is BtResult.Success -> found.value
        }
        val properties = characteristic.properties
        // 只支持 write_no_response 的设备会拒绝带响应的写，所以两种类型都要认。
        val writeType = when {
            properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

            properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ->
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            else -> return BtResult.Failure(BtErrorCode.WRITE_UNSUPPORTED, "BLE 特征不支持写入")
        }
        return session.opMutex.withLock {
            if (session.closed) return@withLock btNotFound(sessionId)
            if (payload.isEmpty()) {
                return@withLock BtResult.Success(BluetoothTransfer(sessionId, 0))
            }
            session.lastUsedAt = System.currentTimeMillis()
            val key = characteristicKey(serviceUuid, characteristicUuid)
            var offset = 0
            // ATT 给 opcode 与属性句柄留 3 个字节；超过这个长度必须分片，否则协议栈会截断或报错。
            // 上游只给 write_no_response 分片，带响应的长写会失败，这里两种都分。
            val chunkLimit = (session.mtu - 3).coerceAtLeast(1)
            while (offset < payload.size) {
                val chunk = payload.copyOfRange(offset, minOf(payload.size, offset + chunkLimit))
                val waiter = CompletableDeferred<Int>()
                session.writeWaiters[key] = waiter
                try {
                    val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(characteristic, chunk, writeType) == BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        characteristic.writeType = writeType
                        @Suppress("DEPRECATION")
                        characteristic.value = chunk
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(characteristic)
                    }
                    if (!started) {
                        return@withLock BtResult.Failure(BtErrorCode.IO_ERROR, "启动 BLE 特征写入失败")
                    }
                    val status = withTimeoutOrNull(WRITE_TIMEOUT_MS) { waiter.await() }
                        ?: return@withLock BtResult.Failure(BtErrorCode.TIMEOUT, "BLE 特征写入超时")
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        return@withLock BtResult.Failure(BtErrorCode.GATT_ERROR, "BLE 特征写入失败: status=$status")
                    }
                } finally {
                    session.writeWaiters.remove(key)
                }
                offset += chunk.size
                if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE && offset < payload.size) {
                    // 无响应写没有完成回调，只能靠小间隔避免把发送队列灌爆。
                    delay(WRITE_CHUNK_GAP_MS)
                }
            }
            BtResult.Success(BluetoothTransfer(sessionId, payload.size))
        }
    }

    suspend fun writeAndReadCharacteristic(
        sessionId: String,
        writeServiceUuid: UUID,
        writeCharacteristicUuid: UUID,
        payload: ByteArray,
        readServiceUuid: UUID,
        readCharacteristicUuid: UUID,
        timeoutMs: Long
    ): BtResult<BluetoothReadResult> {
        return when (val written = writeCharacteristic(sessionId, writeServiceUuid, writeCharacteristicUuid, payload)) {
            is BtResult.Failure -> written
            is BtResult.Success -> readCharacteristic(sessionId, readServiceUuid, readCharacteristicUuid, timeoutMs)
        }
    }

    suspend fun subscribe(
        sessionId: String,
        serviceUuid: UUID,
        characteristicUuid: UUID,
        enable: Boolean,
        timeoutMs: Long
    ): BtResult<BluetoothTransfer> {
        val session = sessions[sessionId] ?: return btNotFound(sessionId)
        val gatt = session.gatt ?: return btNotFound(sessionId)
        val characteristic = when (val found = findCharacteristic(gatt, serviceUuid, characteristicUuid)) {
            is BtResult.Failure -> return found
            is BtResult.Success -> found.value
        }
        val properties = characteristic.properties
        if (enable && properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) == 0) {
            return BtResult.Failure(BtErrorCode.NOTIFY_UNSUPPORTED, "BLE 特征不支持 notify/indicate")
        }
        return session.opMutex.withLock {
            if (session.closed) return@withLock btNotFound(sessionId)
            session.lastUsedAt = System.currentTimeMillis()
            if (!gatt.setCharacteristicNotification(characteristic, enable)) {
                return@withLock BtResult.Failure(BtErrorCode.IO_ERROR, "更新 BLE 通知状态失败")
            }
            val descriptor = characteristic.getDescriptor(BluetoothConstants.cccdUuid)
            if (descriptor == null) {
                // 没有 CCCD 时对端不会真的开始推送；启用必须报错，关闭可以视作已完成。
                return@withLock if (enable) {
                    BtResult.Failure(BtErrorCode.NOTIFY_UNSUPPORTED, "BLE 特征缺少 CCCD 描述符，无法订阅")
                } else {
                    BtResult.Success(BluetoothTransfer(sessionId, 0))
                }
            }
            val value = when {
                !enable -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ->
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

                else -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            val key = characteristicKey(serviceUuid, characteristicUuid)
            val waiter = CompletableDeferred<Int>()
            session.descriptorWaiters[key] = waiter
            try {
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = value
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
                if (!started) {
                    return@withLock BtResult.Failure(BtErrorCode.IO_ERROR, "写入 CCCD 描述符失败")
                }
                val status = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) { waiter.await() }
                    ?: return@withLock BtResult.Failure(BtErrorCode.TIMEOUT, "写入 CCCD 描述符超时（${timeoutMs}ms）")
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    return@withLock BtResult.Failure(BtErrorCode.GATT_ERROR, "写入 CCCD 描述符失败: status=$status")
                }
                BtResult.Success(BluetoothTransfer(sessionId, 0))
            } finally {
                session.descriptorWaiters.remove(key)
            }
        }
    }

    fun readNotifications(sessionId: String, limit: Int): BtResult<BleNotificationsInfo> {
        val session = sessions[sessionId] ?: return btNotFound(sessionId)
        val take = limit.coerceIn(1, BluetoothConstants.MAX_NOTIFICATIONS)
        val items = synchronized(session.notifications) {
            val selected = session.notifications.take(take)
            session.notifications.subList(0, selected.size).clear()
            selected
        }
        session.lastUsedAt = System.currentTimeMillis()
        return BtResult.Success(BleNotificationsInfo(sessionId, items))
    }

    suspend fun close(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        val session = sessions[sessionId] ?: return@withContext false
        dropSession(session, "closed")
        true
    }

    private fun buildGattCallback(session: BleSession): BluetoothGattCallback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            session.connected.complete(true)
                            // 连接成功立刻发现服务：调用方随后的 discoverServices 只是在等这个结果。
                            if (!gatt.discoverServices()) {
                                session.servicesReady.complete(BluetoothGatt.GATT_FAILURE)
                            }
                        } else {
                            session.connected.complete(false)
                            session.servicesReady.complete(BluetoothGatt.GATT_FAILURE)
                        }
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        session.connected.complete(false)
                        session.servicesReady.complete(BluetoothGatt.GATT_FAILURE)
                        failPending(session)
                        dropSession(
                            session,
                            if (session.closed) "closed" else "disconnected",
                            emit = !session.closed
                        )
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                session.servicesReady.complete(status)
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    session.mtu = mtu
                }
            }

            // API 33 起系统改调带 value 的重载；低版本仍必须实现旧重载才能拿到数据。
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                @Suppress("DEPRECATION")
                completeRead(session, characteristic, status, characteristic.value ?: ByteArray(0))
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                completeRead(session, characteristic, status, value)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                val key = characteristicKeyOf(characteristic) ?: return
                session.writeWaiters[key]?.complete(status)
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                @Suppress("DEPRECATION")
                appendNotification(session, characteristic, characteristic.value ?: ByteArray(0))
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                appendNotification(session, characteristic, value)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                val key = descriptorKeyOf(descriptor) ?: return
                session.descriptorWaiters[key]?.complete(status)
            }
        }

    private fun completeRead(
        session: BleSession,
        characteristic: BluetoothGattCharacteristic,
        status: Int,
        value: ByteArray
    ) {
        val key = characteristicKeyOf(characteristic) ?: return
        session.readWaiters[key]?.complete(BleValue(status, value))
    }

    private fun appendNotification(
        session: BleSession,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        val entry = BleNotificationEntry(
            characteristicUuid = characteristic.uuid.toString(),
            bytesRead = value.size,
            text = value.utf8OrNull(),
            dataBase64 = value.takeIf { it.isNotEmpty() }?.encodeBase64(),
            timestamp = System.currentTimeMillis()
        )
        synchronized(session.notifications) {
            if (session.notifications.size >= BluetoothConstants.MAX_NOTIFICATIONS) {
                // 插件读得太慢时丢最旧的，保证内存有界；最新数据对调用方更可能有意义。
                session.notifications.removeAt(0)
            }
            session.notifications += entry
        }
    }

    private fun failPending(session: BleSession) {
        session.readWaiters.values.forEach { it.complete(BleValue(BluetoothGatt.GATT_FAILURE, null)) }
        session.writeWaiters.values.forEach { it.complete(BluetoothGatt.GATT_FAILURE) }
        session.descriptorWaiters.values.forEach { it.complete(BluetoothGatt.GATT_FAILURE) }
    }

    private fun findCharacteristic(
        gatt: BluetoothGatt,
        serviceUuid: UUID,
        characteristicUuid: UUID
    ): BtResult<BluetoothGattCharacteristic> {
        val service: BluetoothGattService = gatt.getService(serviceUuid)
            ?: return BtResult.Failure(BtErrorCode.SERVICE_NOT_FOUND, "BLE 服务不存在: $serviceUuid")
        val characteristic = service.getCharacteristic(characteristicUuid)
            ?: return BtResult.Failure(BtErrorCode.CHARACTERISTIC_NOT_FOUND, "BLE 特征不存在: $characteristicUuid")
        return BtResult.Success(characteristic)
    }

    private fun dropSession(session: BleSession, reason: String, emit: Boolean = true) {
        synchronized(session) {
            if (session.closed) return
            session.closed = true
        }
        sessions.remove(session.id, session)
        failPending(session)
        val gatt = session.gatt
        if (gatt != null) {
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        session.gatt = null
        synchronized(session.notifications) { session.notifications.clear() }
        if (emit) {
            events(BluetoothSessionClosedEvent(session.id, "ble", session.address, reason))
        }
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
    }

    private fun recordScanResult(result: ScanResult, out: MutableMap<String, BluetoothScannedDevice>) {
        val device = result.device
        mergeScannedDevice(
            out,
            BluetoothScannedDevice(
                name = runCatching { device.name }.getOrNull(),
                address = device.address,
                type = deviceTypeName(device.type),
                bondState = bondStateName(device.bondState),
                source = "ble",
                rssi = result.rssi
            )
        )
    }

    private fun busyResult(): BtResult.Failure = BtResult.Failure(
        BtErrorCode.BUSY,
        "蓝牙会话数已达上限（${BluetoothConstants.MAX_SESSIONS}），请先关闭不用的会话"
    )

    private fun newSessionId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    private fun characteristicKey(serviceUuid: UUID, characteristicUuid: UUID): String =
        "$serviceUuid:$characteristicUuid"

    private fun characteristicKeyOf(characteristic: BluetoothGattCharacteristic): String? {
        val serviceUuid = characteristic.service?.uuid ?: return null
        return characteristicKey(serviceUuid, characteristic.uuid)
    }

    private fun descriptorKeyOf(descriptor: BluetoothGattDescriptor): String? {
        val characteristic = descriptor.characteristic ?: return null
        return characteristicKeyOf(characteristic)
    }

    private fun characteristicProperties(properties: Int): List<String> {
        val names = mutableListOf<String>()
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) names += "read"
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) names += "write"
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) names += "write_no_response"
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) names += "notify"
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) names += "indicate"
        return names
    }

    private data class BleValue(val status: Int, val value: ByteArray?)

    private companion object {
        const val WRITE_TIMEOUT_MS = 5_000L
        const val WRITE_CHUNK_GAP_MS = 15L
    }
}
