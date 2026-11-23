package com.aharou.feature.plugin.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.util.Base64
import java.util.UUID

/**
 * 蓝牙能力层的数据模型。
 *
 * 移植自 Operit（LGPL-3.0）的 `BluetoothSessionManager` 与 `ToolResultDataClasses`：
 * 字段名与上游保持一致，后续接 shim 时不需要再做一层字段翻译。
 *
 * 与上游唯一的语义差别：上游用异常表达失败，这里统一用 [BtResult.Failure] 带稳定错误码，
 * 因为插件（JS）侧拿到异常栈没法做分支判断。
 */

/** 错误码会原样交给插件，改名等同于改协议。 */
enum class BtErrorCode {
    /** 设备没有蓝牙适配器。 */
    UNSUPPORTED,

    /** 蓝牙未开启，调用方需要先 requestEnable。 */
    DISABLED,

    /** 缺运行时权限；[BtResult.Failure.missingPermissions] 给出具体缺哪些。 */
    PERMISSION_DENIED,

    /** 参数缺失或格式错误（地址、UUID、载荷）。 */
    PARAM_INVALID,

    /** 会话 id 不存在或已释放。 */
    SESSION_NOT_FOUND,

    /** 连接被对端或协议栈拒绝。 */
    CONNECT_FAILED,

    /** 等待超时（连接、accept、读写、服务发现）。 */
    TIMEOUT,

    /** 套接字/流层面的 IO 失败，通常意味着对端已断开。 */
    IO_ERROR,

    /** 会话数已达上限。 */
    BUSY,

    /** GATT 里找不到该 service。 */
    SERVICE_NOT_FOUND,

    /** GATT 里找不到该 characteristic。 */
    CHARACTERISTIC_NOT_FOUND,

    /** characteristic 的属性不支持写入。 */
    WRITE_UNSUPPORTED,

    /** characteristic 不支持 notify/indicate，或缺少 CCCD 描述符。 */
    NOTIFY_UNSUPPORTED,

    /** GATT 操作返回了非 SUCCESS 状态。 */
    GATT_ERROR,

    /** 系统没有可用的界面来发起蓝牙开启请求。 */
    NO_ACTIVITY
}

sealed interface BtResult<out T> {
    data class Success<T>(val value: T) : BtResult<T>

    data class Failure(
        val code: BtErrorCode,
        val message: String,
        val missingPermissions: List<String> = emptyList()
    ) : BtResult<Nothing>
}

fun <T> BtResult<T>.valueOrNull(): T? = (this as? BtResult.Success<T>)?.value

fun BtResult<*>.isFailure(): Boolean = this is BtResult.Failure

/** 经典蓝牙适配器状态。字段对齐 Operit 的 `BluetoothStateData`。 */
data class BluetoothStateInfo(
    val supported: Boolean,
    val enabled: Boolean,
    val state: String
)

/** 已配对设备。字段对齐 Operit 的 `BluetoothDeviceData`。 */
data class BluetoothDeviceInfo(
    val name: String?,
    val address: String,
    val type: String,
    val bondState: String
)

/** 扫描到的设备。字段对齐 Operit 的 `BluetoothScannedDeviceData`。 */
data class BluetoothScannedDevice(
    val name: String?,
    val address: String,
    val type: String,
    val bondState: String,
    val source: String,
    val rssi: Int? = null
)

data class BluetoothScanResult(
    val devices: List<BluetoothScannedDevice>,
    val durationMs: Long,
    val includesBle: Boolean
)

/** 会话信息。mode 取 `classic` / `classic_listener` / `ble`，与上游一致。 */
data class BluetoothSessionInfo(
    val sessionId: String,
    val address: String,
    val mode: String
)

data class BluetoothTransfer(
    val sessionId: String,
    val bytesWritten: Int
)

data class BluetoothReadResult(
    val sessionId: String,
    val bytesRead: Int,
    val text: String?,
    val dataBase64: String?
)

data class BleCharacteristicInfo(
    val uuid: String,
    val properties: List<String>
)

data class BleServiceInfo(
    val uuid: String,
    val characteristics: List<BleCharacteristicInfo>
)

data class BleServicesInfo(
    val sessionId: String,
    val services: List<BleServiceInfo>
)

data class BleNotificationEntry(
    val characteristicUuid: String,
    val bytesRead: Int,
    val text: String?,
    val dataBase64: String?,
    val timestamp: Long
)

data class BleNotificationsInfo(
    val sessionId: String,
    val notifications: List<BleNotificationEntry>
)

/** 开启蓝牙的结果：系统弹窗由系统自己收尾，这里只能回报「是否已开 / 是否已拉起弹窗」。 */
data class BluetoothEnableResult(
    val alreadyEnabled: Boolean,
    val requestLaunched: Boolean
)

/**
 * 权限现状。能力层不弹窗，调用方（UI 侧）按 [missing] 去申请即可。
 * Android 12+ 与 11 及以下需要的权限不同，两套都列出来避免调用方自己判断版本。
 */
data class BluetoothPermissionState(
    val granted: Boolean,
    val missing: List<String>,
    val sdkInt: Int,
    val requiredForConnect: List<String>,
    val requiredForScan: List<String>
)

/** 会话被释放的通知（主动 close、对端断开、超时回收都会发）。 */
data class BluetoothSessionClosedEvent(
    val sessionId: String,
    val mode: String,
    val address: String,
    val reason: String
)

internal object BluetoothConstants {
    const val DEFAULT_SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"

    /** Client Characteristic Configuration Descriptor：订阅通知必须写它，对端才会开始推送。 */
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    const val DEFAULT_SCAN_MS = 12_000L
    const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L
    const val DEFAULT_READ_TIMEOUT_MS = 5_000L
    const val DEFAULT_DISCOVERY_TIMEOUT_MS = 10_000L
    const val DEFAULT_ACCEPT_TIMEOUT_MS = 30_000L

    /** 会话数上限：经典会话 + 监听器 + BLE 会话共用这个额度，防止插件泄漏把句柄耗光。 */
    const val MAX_SESSIONS = 16

    /** 超过 30 分钟没被使用的会话自动释放；BLE 的 gatt 与经典 socket 都不会自己超时。 */
    const val SESSION_IDLE_TIMEOUT_MS = 30 * 60 * 1000L
    const val REAPER_INTERVAL_MS = 60 * 1000L

    /** 单会话通知缓冲上限；插件长时间不读时丢弃最旧的，避免无上限吃内存。 */
    const val MAX_NOTIFICATIONS = 256

    val defaultSppUuid: UUID = UUID.fromString(DEFAULT_SPP_UUID)
    val cccdUuid: UUID = UUID.fromString(CCCD_UUID)
}

internal fun deviceTypeName(type: Int): String = when (type) {
    BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
    BluetoothDevice.DEVICE_TYPE_LE -> "le"
    BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"
    BluetoothDevice.DEVICE_TYPE_UNKNOWN -> "unknown"
    else -> "unknown"
}

internal fun bondStateName(state: Int): String = when (state) {
    BluetoothDevice.BOND_NONE -> "none"
    BluetoothDevice.BOND_BONDING -> "bonding"
    BluetoothDevice.BOND_BONDED -> "bonded"
    else -> "unknown"
}

internal fun adapterStateName(state: Int): String = when (state) {
    BluetoothAdapter.STATE_OFF -> "off"
    BluetoothAdapter.STATE_TURNING_ON -> "turning_on"
    BluetoothAdapter.STATE_ON -> "on"
    BluetoothAdapter.STATE_TURNING_OFF -> "turning_off"
    else -> "unknown"
}

/**
 * 经典发现与 BLE 扫描并行跑，同一设备可能被两路同时报上来。
 * 合并而不是覆盖：先到的 source 保留，名字/RSSI 用后到的补空缺。
 */
internal fun mergeScannedDevice(
    out: MutableMap<String, BluetoothScannedDevice>,
    incoming: BluetoothScannedDevice
) {
    // 经典发现与 BLE 扫描并发写同一个 map，读改写必须原子，否则两路结果会互相覆盖。
    synchronized(out) {
        val existing = out[incoming.address]
        out[incoming.address] = if (existing == null) {
            incoming
        } else {
            existing.copy(
                name = existing.name ?: incoming.name,
                type = if (existing.type == "unknown") incoming.type else existing.type,
                bondState = if (existing.bondState == "unknown") incoming.bondState else existing.bondState,
                source = if (existing.source == incoming.source) existing.source else "classic+ble",
                rssi = existing.rssi ?: incoming.rssi
            )
        }
    }
}

internal fun ByteArray.encodeBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

internal fun ByteArray.utf8OrNull(): String? =
    takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8)

internal fun <T> btNotFound(sessionId: String): BtResult<T> =
    BtResult.Failure(BtErrorCode.SESSION_NOT_FOUND, "蓝牙会话不存在: $sessionId")
