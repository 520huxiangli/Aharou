package com.aharou.feature.plugin.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

/**
 * 插件侧蓝牙能力入口。
 *
 * 移植自 Operit（LGPL-3.0）的 `BluetoothSessionManager` 与 `Tools.System.bluetooth.*` 工具层，
 * 方法名与上游保持一致（scan / connect / listen / accept / send / read / sendAndRead / close、
 * connectBle / discoverServices / readCharacteristic / writeCharacteristic /
 * writeAndReadCharacteristic / subscribe / readNotifications），方便后续 shim 直接一一对应。
 *
 * 三条与上游不同的约定：
 * - **不抛异常**：所有入口返回 [BtResult]，失败带稳定错误码，插件侧可据此分支；
 * - **不弹窗**：运行时权限缺失时只返回 `PERMISSION_DENIED` 与缺失清单，由 UI 层去申请；
 *   只有「开启蓝牙」会拉起系统自带界面（与上游的 requestEnable 行为一致）；
 * - **会话有生命周期**：超时、对端断开、主动 close 都会释放资源并通过 [sessionClosedEvents] 通知。
 */
@Singleton
class BluetoothController @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    private val sessionEvents = MutableSharedFlow<BluetoothSessionClosedEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** 会话被释放的通知：主动 close、对端断开、空闲回收都会发，reason 区分原因。 */
    val sessionClosedEvents: SharedFlow<BluetoothSessionClosedEvent> = sessionEvents.asSharedFlow()

    private val classicChannel = BluetoothClassicChannel(context) { sessionEvents.tryEmit(it) }
    private val bleChannel = BluetoothBleChannel(context) { sessionEvents.tryEmit(it) }

    /** 权限现状；调用方拿 missing 去申请，再重试原操作。 */
    fun permissionState(): BluetoothPermissionState = BluetoothPermissionGate.state(context)

    suspend fun state(): BtResult<BluetoothStateInfo> = guarded("读取蓝牙状态") {
        val adapter = adapter()
            // 没蓝牙的设备按「不支持」回报而不是报错：插件可以先问状态再决定要不要用其他能力。
            ?: return@guarded BtResult.Success(BluetoothStateInfo(supported = false, enabled = false, state = "unsupported"))
        BluetoothPermissionGate.require(context, BluetoothPermissionGate.requiredForConnect(), "读取蓝牙状态")
            ?.let { return@guarded it }
        BtResult.Success(
            BluetoothStateInfo(
                supported = true,
                enabled = adapter.isEnabled,
                state = adapterStateName(adapter.state)
            )
        )
    }

    /**
     * 拉起系统「开启蓝牙」界面。用户选完之前拿不到结果，返回只表示弹窗已打开；
     * 调用方随后用 [state] 查询真实状态。
     */
    suspend fun requestEnable(): BtResult<BluetoothEnableResult> = guarded("开启蓝牙") {
        val adapter = adapter() ?: return@guarded unsupported()
        BluetoothPermissionGate.require(context, BluetoothPermissionGate.requiredForConnect(), "开启蓝牙")
            ?.let { return@guarded it }
        if (adapter.isEnabled) {
            return@guarded BtResult.Success(BluetoothEnableResult(alreadyEnabled = true, requestLaunched = false))
        }
        withContext(Dispatchers.Main) {
            try {
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                BtResult.Success(BluetoothEnableResult(alreadyEnabled = false, requestLaunched = true))
            } catch (e: ActivityNotFoundException) {
                BtResult.Failure(BtErrorCode.NO_ACTIVITY, "系统没有可用的蓝牙开启界面")
            }
        }
    }

    suspend fun bondedDevices(): BtResult<List<BluetoothDeviceInfo>> = guarded("列出已配对设备") {
        val adapter = adapter() ?: return@guarded unsupported()
        BluetoothPermissionGate.require(context, BluetoothPermissionGate.requiredForConnect(), "列出已配对设备")
            ?.let { return@guarded it }
        withContext(Dispatchers.IO) {
            val devices = adapter.bondedDevices.orEmpty()
                .map { device ->
                    BluetoothDeviceInfo(
                        name = runCatching { device.name }.getOrNull(),
                        address = device.address,
                        type = deviceTypeName(device.type),
                        bondState = bondStateName(device.bondState)
                    )
                }
                .sortedWith(compareBy({ it.name ?: "" }, { it.address }))
            BtResult.Success(devices)
        }
    }

    /** 经典发现（ACTION_FOUND）与 BLE 扫描并行，[includeBle] 为 false 时只跑经典。 */
    suspend fun scan(
        durationMs: Long = BluetoothConstants.DEFAULT_SCAN_MS,
        includeBle: Boolean = true
    ): BtResult<BluetoothScanResult> = guarded("扫描蓝牙设备") {
        val adapter = adapter() ?: return@guarded unsupported()
        BluetoothPermissionGate.require(context, BluetoothPermissionGate.requiredForScan(), "扫描蓝牙设备")
            ?.let { return@guarded it }
        if (!adapter.isEnabled) return@guarded disabled()
        val duration = durationMs.coerceIn(1_000L, MAX_SCAN_MS)
        val found = ConcurrentHashMap<String, BluetoothScannedDevice>()
        coroutineScope {
            val classicJob = async { runCatching { classicChannel.scanDevices(adapter, duration, found) } }
            val bleJob = if (includeBle) {
                async { runCatching { bleChannel.scanDevices(adapter, duration, found) } }
            } else {
                null
            }
            classicJob.await().exceptionOrNull()?.let { throw it }
            bleJob?.await()?.exceptionOrNull()?.let { throw it }
        }
        BtResult.Success(
            BluetoothScanResult(
                devices = found.values.sortedWith(compareBy({ it.name ?: "" }, { it.address })),
                durationMs = duration,
                includesBle = includeBle
            )
        )
    }

    /** RFCOMM 连接；[uuid] 为空时用 SPP 默认 UUID。 */
    suspend fun connect(
        address: String,
        uuid: String? = null,
        timeoutMs: Long = BluetoothConstants.DEFAULT_CONNECT_TIMEOUT_MS
    ): BtResult<BluetoothSessionInfo> = guarded("连接经典蓝牙") {
        val target = address.trim()
        if (target.isEmpty()) return@guarded paramFailure("缺少 address")
        val parsed = parseUuidOrDefault(uuid) ?: return@guarded paramFailure("UUID 格式无效: $uuid")
        val adapter = adapter() ?: return@guarded unsupported()
        BluetoothPermissionGate.require(context, BluetoothPermissionGate.requiredForConnect(), "连接经典蓝牙")
            ?.let { return@guarded it }
        if (!adapter.isEnabled) return@guarded disabled()
        classicChannel.connect(adapter, target, parsed, timeoutMs.coerceIn(1_000L, MAX_CONNECT_TIMEOUT_MS))
    }

    /** 开始监听（serverSocket）；随后用 [accept] 等对端连上来。 */
    suspend fun listen(
        name: String = "Aharou Bluetooth",
        uuid: String? = null
    ): BtResult<BluetoothSessionInfo> = guarded("监听经典蓝牙") {
        val listenerName = name.trim().ifEmpty { "Aharou Bluetooth" }
        val parsed = parseUuidOrDefault(uuid) ?: return@guarded paramFailure("UUID 格式无效: $uuid")
        val adapter = adapter() ?: return@guarded unsupported()
        BluetoothPermissionGate.require(context, BluetoothPermissionGate.requiredForConnect(), "监听经典蓝牙")
            ?.let { return@guarded it }
        if (!adapter.isEnabled) return@guarded disabled()
        classicChannel.listen(adapter, listenerName, parsed)
    }

    suspend fun accept(
        listenerId: String,
        timeoutMs: Long = BluetoothConstants.DEFAULT_ACCEPT_TIMEOUT_MS
    ): BtResult<BluetoothSessionInfo> = guarded("等待经典蓝牙连接") {
        val id = listenerId.trim()
        if (id.isEmpty()) return@guarded paramFailure("缺少 listener_id")
        BluetoothPermissionGate.require(context, BluetoothPermissionGate.requiredForConnect(), "等待经典蓝牙连接")
            ?.let { return@guarded it }
        classicChannel.accept(id, timeoutMs.coerceIn(1_000L, MAX_ACCEPT_TIMEOUT_MS))
    }

    /** 发送载荷：[text] 按 UTF-8 编码，[dataBase64] 优先，二者都为空视为参数错误。 */
    suspend fun send(
        sessionId: String,
        text: String? = null,
        dataBase64: String? = null
    ): BtResult<BluetoothTransfer> = guarded("发送蓝牙数据") {
        val id = sessionId.trim()
        if (id.isEmpty()) return@guarded paramFailure("缺少 session_id")
        val payload = decodePayload(text, dataBase64) ?: return@guarded paramFailure("需要 text 或 data_base64")
        BluetoothPermissionGate.require(context, BluetoothPermissionGate.requiredForConnect(), "发送蓝牙数据")
            ?.let { return@guarded it }
        classicChannel.send(id, payload)
    }

    /**
     * 读取数据：超时且没读到任何字节时返回 `bytesRead=0` 的成功结果（与上游一致），
     * 只有对端关闭或 IO 失败才报错。
     */
    suspend fun read(
        sessionId: String,
        maxBytes: Int = 4096,
        timeoutMs: Long = BluetoothConstants.DEFAULT_READ_TIMEOUT_MS
    ): BtResult<BluetoothReadResult> = guarded("读取蓝牙数据") {
        val id = sessionId.trim()
        if (id.isEmpty()) return@guarded paramFailure("缺少 session_id")
        BluetoothPermissionGate.require(context, BluetoothPermissionGate.requiredForConnect(), "读取蓝牙数据")
            ?.let { return@guarded it }
        classicChannel.read(id, maxBytes, timeoutMs.coerceIn(0L, MAX_READ_TIMEOUT_MS))
    }

    suspend fun sendAndRead(
        sessionId: String,
        text: String? = null,
        dataBase64: String? = null,
        maxBytes: Int = 4096,
        timeoutMs: Long = BluetoothConstants.DEFAULT_READ_TIMEOUT_MS
    ): BtResult<BluetoothReadResult> = guarded("发送并读取蓝牙数据") {
        val id = sessionId.trim()
        if (id.isEmpty()) return@guarded paramFailure("缺少 session_id")
        val payload = decodePayload(text, dataBase64) ?: return@guarded paramFailure("需要 text 或 data_base64")
        BluetoothPermissionGate.require(context, BluetoothPermissionGate.requiredForConnect(), "发送并读取蓝牙数据")
            ?.let { return@guarded it }
        classicChannel.sendAndRead(id, payload, maxBytes, timeoutMs.coerceIn(0L, MAX_READ_TIMEOUT_MS))
    }

    /** 关闭经典会话、监听器或 BLE 会话；同一入口按 id 查找，找不到返回 SESSION_NOT_FOUND。 */
    suspend fun close(sessionId: String): BtResult<Unit> = guarded("关闭蓝牙会话") {
        val id = sessionId.trim()
        if (id.isEmpty()) return@guarded paramFailure("缺少 session_id")
        if (classicChannel.close(id)) return@guarded BtResult.Success(Unit)
        if (bleChannel.close(id)) return@guarded BtResult.Success(Unit)
        btNotFound(id)
    }

    /** 连接 BLE 设备；[autoConnect] 为 true 时系统会在设备出现后自动重连，可能久等，放宽 timeoutMs。 */
    suspend fun connectBle(
        address: String,
        autoConnect: Boolean = false,
        timeoutMs: Long = BluetoothConstants.DEFAULT_CONNECT_TIMEOUT_MS
    ): BtResult<BluetoothSessionInfo> = guarded("连接 BLE 设备") {
        val target = address.trim()
        if (target.isEmpty()) return@guarded paramFailure("缺少 address")
        val adapter = adapter() ?: return@guarded unsupported()
        BluetoothPermissionGate.require(context, BluetoothPermissionGate.requiredForConnect(), "连接 BLE 设备")
            ?.let { return@guarded it }
        if (!adapter.isEnabled) return@guarded disabled()
        bleChannel.connect(adapter, target, autoConnect, timeoutMs.coerceIn(1_000L, MAX_CONNECT_TIMEOUT_MS))
    }

    suspend fun discoverServices(
        sessionId: String,
        timeoutMs: Long = BluetoothConstants.DEFAULT_DISCOVERY_TIMEOUT_MS
    ): BtResult<BleServicesInfo> = guarded("发现 BLE 服务") {
        val id = sessionId.trim()
        if (id.isEmpty()) return@guarded paramFailure("缺少 session_id")
        BluetoothPermissionGate.require(context, BluetoothPermissionGate.requiredForConnect(), "发现 BLE 服务")
            ?.let { return@guarded it }
        bleChannel.discoverServices(id, timeoutMs.coerceIn(1_000L, MAX_READ_TIMEOUT_MS))
    }

    suspend fun readCharacteristic(
        sessionId: String,
        serviceUuid: String,
        characteristicUuid: String,
        timeoutMs: Long = BluetoothConstants.DEFAULT_READ_TIMEOUT_MS
    ): BtResult<BluetoothReadResult> = guarded("读取 BLE 特征") {
        val id = sessionId.trim()
        if (id.isEmpty()) return@guarded paramFailure("缺少 session_id")
        val service = parseUuid(serviceUuid) ?: return@guarded paramFailure("service_uuid 格式无效: $serviceUuid")
        val characteristic = parseUuid(characteristicUuid)
            ?: return@guarded paramFailure("characteristic_uuid 格式无效: $characteristicUuid")
        BluetoothPermissionGate.require(context, BluetoothPermissionGate.requiredForConnect(), "读取 BLE 特征")
            ?.let { return@guarded it }
        bleChannel.readCharacteristic(id, service, characteristic, timeoutMs.coerceIn(1_000L, MAX_READ_TIMEOUT_MS))
    }

    suspend fun writeCharacteristic(
        sessionId: String,
        serviceUuid: String,
        characteristicUuid: String,
        text: String? = null,
        dataBase64: String? = null
    ): BtResult<BluetoothTransfer> = guarded("写入 BLE 特征") {
        val id = sessionId.trim()
        if (id.isEmpty()) return@guarded paramFailure("缺少 session_id")
        val service = parseUuid(serviceUuid) ?: return@guarded paramFailure("service_uuid 格式无效: $serviceUuid")
        val characteristic = parseUuid(characteristicUuid)
            ?: return@guarded paramFailure("characteristic_uuid 格式无效: $characteristicUuid")
        val payload = decodePayload(text, dataBase64) ?: return@guarded paramFailure("需要 text 或 data_base64")
        BluetoothPermissionGate.require(context, BluetoothPermissionGate.requiredForConnect(), "写入 BLE 特征")
            ?.let { return@guarded it }
        bleChannel.writeCharacteristic(id, service, characteristic, payload)
    }

    suspend fun writeAndReadCharacteristic(
        sessionId: String,
        writeServiceUuid: String,
        writeCharacteristicUuid: String,
        text: String? = null,
        dataBase64: String? = null,
        readServiceUuid: String,
        readCharacteristicUuid: String,
        timeoutMs: Long = BluetoothConstants.DEFAULT_READ_TIMEOUT_MS
    ): BtResult<BluetoothReadResult> = guarded("写入并读取 BLE 特征") {
        val id = sessionId.trim()
        if (id.isEmpty()) return@guarded paramFailure("缺少 session_id")
        val writeService = parseUuid(writeServiceUuid)
            ?: return@guarded paramFailure("write_service_uuid 格式无效: $writeServiceUuid")
        val writeCharacteristic = parseUuid(writeCharacteristicUuid)
            ?: return@guarded paramFailure("write_characteristic_uuid 格式无效: $writeCharacteristicUuid")
        val readService = parseUuid(readServiceUuid)
            ?: return@guarded paramFailure("read_service_uuid 格式无效: $readServiceUuid")
        val readCharacteristic = parseUuid(readCharacteristicUuid)
            ?: return@guarded paramFailure("read_characteristic_uuid 格式无效: $readCharacteristicUuid")
        val payload = decodePayload(text, dataBase64) ?: return@guarded paramFailure("需要 text 或 data_base64")
        BluetoothPermissionGate.require(context, BluetoothPermissionGate.requiredForConnect(), "写入并读取 BLE 特征")
            ?.let { return@guarded it }
        bleChannel.writeAndReadCharacteristic(
            id,
            writeService,
            writeCharacteristic,
            payload,
            readService,
            readCharacteristic,
            timeoutMs.coerceIn(1_000L, MAX_READ_TIMEOUT_MS)
        )
    }

    /** 订阅/取消通知：内部同时写 CCCD 描述符，等描述符写完成才返回。 */
    suspend fun subscribe(
        sessionId: String,
        serviceUuid: String,
        characteristicUuid: String,
        enable: Boolean = true,
        timeoutMs: Long = BluetoothConstants.DEFAULT_READ_TIMEOUT_MS
    ): BtResult<BluetoothTransfer> = guarded("订阅 BLE 通知") {
        val id = sessionId.trim()
        if (id.isEmpty()) return@guarded paramFailure("缺少 session_id")
        val service = parseUuid(serviceUuid) ?: return@guarded paramFailure("service_uuid 格式无效: $serviceUuid")
        val characteristic = parseUuid(characteristicUuid)
            ?: return@guarded paramFailure("characteristic_uuid 格式无效: $characteristicUuid")
        BluetoothPermissionGate.require(context, BluetoothPermissionGate.requiredForConnect(), "订阅 BLE 通知")
            ?.let { return@guarded it }
        bleChannel.subscribe(id, service, characteristic, enable, timeoutMs.coerceIn(1_000L, MAX_READ_TIMEOUT_MS))
    }

    /** 取走缓冲区里最早的一批通知；读走即清空，limit 上限为缓冲容量。 */
    fun readNotifications(
        sessionId: String,
        limit: Int = 20
    ): BtResult<BleNotificationsInfo> {
        val id = sessionId.trim()
        if (id.isEmpty()) return paramFailure("缺少 session_id")
        return bleChannel.readNotifications(id, limit)
    }

    private suspend fun <T> guarded(
        action: String,
        block: suspend () -> BtResult<T>
    ): BtResult<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: SecurityException) {
        BtResult.Failure(
            code = BtErrorCode.PERMISSION_DENIED,
            message = "$action 缺少蓝牙权限: ${e.message ?: "被系统拒绝"}",
            missingPermissions = BluetoothPermissionGate.missing(
                context,
                (BluetoothPermissionGate.requiredForConnect() + BluetoothPermissionGate.requiredForScan()).distinct()
            )
        )
    } catch (e: Exception) {
        BtResult.Failure(
            code = BtErrorCode.IO_ERROR,
            message = "$action 失败: ${e.message ?: e::class.java.simpleName}"
        )
    }

    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private fun unsupported(): BtResult.Failure =
        BtResult.Failure(BtErrorCode.UNSUPPORTED, "此设备不支持蓝牙")

    private fun disabled(): BtResult.Failure =
        BtResult.Failure(BtErrorCode.DISABLED, "蓝牙未开启，请先开启蓝牙")

    private fun paramFailure(message: String): BtResult.Failure =
        BtResult.Failure(BtErrorCode.PARAM_INVALID, message)

    private fun parseUuidOrDefault(raw: String?): UUID? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return BluetoothConstants.defaultSppUuid
        return parseUuid(value)
    }

    private fun parseUuid(raw: String?): UUID? =
        runCatching { UUID.fromString(raw?.trim().orEmpty()) }.getOrNull()

    private fun decodePayload(text: String?, dataBase64: String?): ByteArray? = when {
        dataBase64 != null -> runCatching { Base64.decode(dataBase64, Base64.DEFAULT) }.getOrNull()
        text != null -> text.toByteArray(Charsets.UTF_8)
        else -> null
    }

    private companion object {
        const val MAX_SCAN_MS = 60_000L
        const val MAX_CONNECT_TIMEOUT_MS = 120_000L
        const val MAX_ACCEPT_TIMEOUT_MS = 600_000L
        const val MAX_READ_TIMEOUT_MS = 60_000L
    }
}
