package com.aharou.feature.plugin.runtime

import com.aharou.feature.plugin.bluetooth.BleCharacteristicInfo
import com.aharou.feature.plugin.bluetooth.BleNotificationsInfo
import com.aharou.feature.plugin.bluetooth.BleServiceInfo
import com.aharou.feature.plugin.bluetooth.BluetoothController
import com.aharou.feature.plugin.bluetooth.BluetoothDeviceInfo
import com.aharou.feature.plugin.bluetooth.BluetoothReadResult
import com.aharou.feature.plugin.bluetooth.BluetoothScanResult
import com.aharou.feature.plugin.bluetooth.BluetoothScannedDevice
import com.aharou.feature.plugin.bluetooth.BluetoothSessionInfo
import com.aharou.feature.plugin.bluetooth.BluetoothTransfer
import com.aharou.feature.plugin.bluetooth.BtResult
import com.aharou.feature.plugin.domain.PluginHostException
import com.aharou.feature.plugin.domain.PluginManager
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * `bluetooth.*` 与 `ble.*` 宿主方法（共 19 条），对接 [BluetoothController]。
 *
 * 这层只做三件事：snake_case 入参归一化（Operit 的 JS 就是这么传的）、
 * [BtResult] 转 JSON、失败时把稳定错误码原样抛成 [PluginHostException]。
 * 能力本身（权限、会话、超时回收）全在控制器里，这里不复制任何蓝牙逻辑。
 *
 * 大小写与返回结构按 Operit 的 `ToolResultDataClasses` 对齐：
 * `sessionId` / `bytesRead` / `dataBase64` / `characteristicUuid` 等字段名照抄，
 * 按 Operit 写的插件不改字段就能读。
 */
@Singleton
class PluginBluetoothService @Inject constructor(
    private val controller: BluetoothController,
    private val pluginManager: PluginManager,
) {

    // ---------------- 经典蓝牙（12） ----------------

    suspend fun permission(pluginId: String, params: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            ensureActive(pluginId)
            val state = controller.permissionState()
            buildJsonObject {
                put("granted", state.granted)
                put("missing", stringArray(state.missing))
                put("sdkInt", state.sdkInt)
                put("requiredForConnect", stringArray(state.requiredForConnect))
                put("requiredForScan", stringArray(state.requiredForScan))
                // 插件进程弹不了系统权限框，只能回报缺什么，由 App 界面去申请
                put("requestable", false)
                put(
                    "hint",
                    if (state.granted) {
                        "蓝牙权限已就绪"
                    } else {
                        "缺运行时权限：${state.missing.joinToString(", ")}；请在 Aharou 设置里授予蓝牙权限后重试"
                    }
                )
            }
        }

    suspend fun state(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        val info = controller.state().orThrow()
        buildJsonObject {
            put("supported", info.supported)
            put("enabled", info.enabled)
            put("state", info.state)
        }
    }

    suspend fun requestEnable(pluginId: String, params: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            ensureActive(pluginId)
            val result = controller.requestEnable().orThrow()
            buildJsonObject {
                put("alreadyEnabled", result.alreadyEnabled)
                put("requestLaunched", result.requestLaunched)
                // 系统弹窗由用户自己点，这里只能告知「弹没弹出来」
                put("message", if (result.alreadyEnabled) "蓝牙已开启" else "已拉起系统开启蓝牙界面，请用户在弹窗里确认")
            }
        }

    suspend fun bondedDevices(pluginId: String, params: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            ensureActive(pluginId)
            val devices = controller.bondedDevices().orThrow()
            buildJsonObject {
                put("totalCount", devices.size)
                put("devices", buildJsonArray { devices.forEach { add(deviceJson(it)) } })
            }
        }

    suspend fun scan(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        val duration = params.long("duration_ms", "durationMs")
        val includeBle = params.bool("include_ble", "includeBle") ?: true
        val result = controller.scan(duration ?: DEFAULT_SCAN_MS, includeBle).orThrow()
        scanJson(result)
    }

    suspend fun connect(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        val address = params.requireText("address")
        val session = controller
            .connect(
                address = address,
                uuid = params.text("uuid"),
                timeoutMs = params.long("timeout_ms", "timeoutMs") ?: DEFAULT_CONNECT_TIMEOUT_MS,
            )
            .orThrow()
        sessionJson(session)
    }

    suspend fun listen(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        val session = controller
            .listen(
                name = params.text("name")?.takeIf { it.isNotBlank() } ?: DEFAULT_LISTENER_NAME,
                uuid = params.text("uuid"),
            )
            .orThrow()
        sessionJson(session)
    }

    suspend fun accept(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        val listenerId = params.text("listener_session_id") ?: params.text("listener_id")
        ?: throw PluginHostException("PARAM_MISSING", "missing parameter: listener_session_id")
        val session = controller
            .accept(
                listenerId = listenerId,
                timeoutMs = params.long("timeout_ms", "timeoutMs") ?: DEFAULT_ACCEPT_TIMEOUT_MS,
            )
            .orThrow()
        sessionJson(session)
    }

    suspend fun send(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        val transfer = controller
            .send(
                sessionId = params.requireSessionId(),
                text = params.text("text"),
                dataBase64 = params.text("data_base64") ?: params.text("dataBase64"),
            )
            .orThrow()
        transferJson(transfer)
    }

    suspend fun read(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        val result = controller
            .read(
                sessionId = params.requireSessionId(),
                maxBytes = params.int("max_bytes", "maxBytes") ?: DEFAULT_MAX_BYTES,
                timeoutMs = params.long("timeout_ms", "timeoutMs") ?: DEFAULT_READ_TIMEOUT_MS,
            )
            .orThrow()
        readJson(result)
    }

    suspend fun sendAndRead(pluginId: String, params: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            ensureActive(pluginId)
            val result = controller
                .sendAndRead(
                    sessionId = params.requireSessionId(),
                    text = params.text("text"),
                    dataBase64 = params.text("data_base64") ?: params.text("dataBase64"),
                    maxBytes = params.int("max_bytes", "maxBytes") ?: DEFAULT_MAX_BYTES,
                    timeoutMs = params.long("timeout_ms", "timeoutMs") ?: DEFAULT_READ_TIMEOUT_MS,
                )
                .orThrow()
            readJson(result)
        }

    /** 经典会话、监听器、BLE 会话共用一个入口，控制器按 id 自己找。 */
    suspend fun close(pluginId: String, params: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        ensureActive(pluginId)
        controller.close(params.requireSessionId()).orThrow()
        buildJsonObject { put("ok", true) }
    }

    // ---------------- BLE（7） ----------------

    suspend fun bleConnect(pluginId: String, params: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            ensureActive(pluginId)
            val session = controller
                .connectBle(
                    address = params.requireText("address"),
                    autoConnect = params.bool("auto_connect", "autoConnect") ?: false,
                    timeoutMs = params.long("timeout_ms", "timeoutMs") ?: DEFAULT_CONNECT_TIMEOUT_MS,
                )
                .orThrow()
            sessionJson(session)
        }

    suspend fun bleDiscoverServices(pluginId: String, params: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            ensureActive(pluginId)
            val info = controller
                .discoverServices(
                    sessionId = params.requireSessionId(),
                    timeoutMs = params.long("timeout_ms", "timeoutMs") ?: DEFAULT_DISCOVERY_TIMEOUT_MS,
                )
                .orThrow()
            buildJsonObject {
                put("sessionId", info.sessionId)
                put("services", buildJsonArray { info.services.forEach { add(serviceJson(it)) } })
            }
        }

    suspend fun bleReadCharacteristic(pluginId: String, params: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            ensureActive(pluginId)
            val result = controller
                .readCharacteristic(
                    sessionId = params.requireSessionId(),
                    serviceUuid = params.requireText("service_uuid"),
                    characteristicUuid = params.requireText("characteristic_uuid"),
                    timeoutMs = params.long("timeout_ms", "timeoutMs") ?: DEFAULT_READ_TIMEOUT_MS,
                )
                .orThrow()
            readJson(result)
        }

    suspend fun bleWriteCharacteristic(pluginId: String, params: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            ensureActive(pluginId)
            val transfer = controller
                .writeCharacteristic(
                    sessionId = params.requireSessionId(),
                    serviceUuid = params.requireText("service_uuid"),
                    characteristicUuid = params.requireText("characteristic_uuid"),
                    text = params.text("text"),
                    dataBase64 = params.text("data_base64") ?: params.text("dataBase64"),
                )
                .orThrow()
            transferJson(transfer)
        }

    suspend fun bleWriteAndReadCharacteristic(pluginId: String, params: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            ensureActive(pluginId)
            val result = controller
                .writeAndReadCharacteristic(
                    sessionId = params.requireSessionId(),
                    writeServiceUuid = params.requireText("write_service_uuid"),
                    writeCharacteristicUuid = params.requireText("write_characteristic_uuid"),
                    text = params.text("text"),
                    dataBase64 = params.text("data_base64") ?: params.text("dataBase64"),
                    readServiceUuid = params.requireText("read_service_uuid"),
                    readCharacteristicUuid = params.requireText("read_characteristic_uuid"),
                    timeoutMs = params.long("timeout_ms", "timeoutMs") ?: DEFAULT_READ_TIMEOUT_MS,
                )
                .orThrow()
            readJson(result)
        }

    suspend fun bleSubscribe(pluginId: String, params: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            ensureActive(pluginId)
            val transfer = controller
                .subscribe(
                    sessionId = params.requireSessionId(),
                    serviceUuid = params.requireText("service_uuid"),
                    characteristicUuid = params.requireText("characteristic_uuid"),
                    enable = params.bool("enable") ?: true,
                    timeoutMs = params.long("timeout_ms", "timeoutMs") ?: DEFAULT_READ_TIMEOUT_MS,
                )
                .orThrow()
            transferJson(transfer)
        }

    /** 通知是「取走即清空」的语义：插件该轮没读，下一轮就只剩新推的。 */
    suspend fun bleReadNotifications(pluginId: String, params: JsonObject): JsonElement =
        withContext(Dispatchers.IO) {
            ensureActive(pluginId)
            val info = controller
                .readNotifications(
                    sessionId = params.requireSessionId(),
                    limit = params.int("limit") ?: DEFAULT_NOTIFICATION_LIMIT,
                )
                .orThrow()
            notificationsJson(info)
        }

    // ---------------- 序列化 ----------------

    private fun <T> BtResult<T>.orThrow(): T {
        if (this is BtResult.Failure) {
            val suffix = if (missingPermissions.isEmpty()) {
                ""
            } else {
                "（缺少权限：${missingPermissions.joinToString(", ")}）"
            }
            throw PluginHostException(code.name, message + suffix)
        }
        // 失败已排除，剩下的只有 Success；型变参数在星投影里丢了，只能显式转回 T
        @Suppress("UNCHECKED_CAST")
        return (this as BtResult.Success<T>).value
    }

    private fun scanJson(result: BluetoothScanResult): JsonObject = buildJsonObject {
        put("durationMs", result.durationMs)
        put("includesBle", result.includesBle)
        put("totalCount", result.devices.size)
        put("devices", buildJsonArray { result.devices.forEach { add(scannedJson(it)) } })
    }

    private fun deviceJson(device: BluetoothDeviceInfo): JsonObject = buildJsonObject {
        if (device.name == null) put("name", JsonNull) else put("name", device.name)
        put("address", device.address)
        put("type", device.type)
        put("bondState", device.bondState)
    }

    private fun scannedJson(device: BluetoothScannedDevice): JsonObject = buildJsonObject {
        if (device.name == null) put("name", JsonNull) else put("name", device.name)
        put("address", device.address)
        put("type", device.type)
        put("bondState", device.bondState)
        put("source", device.source)
        if (device.rssi == null) put("rssi", JsonNull) else put("rssi", device.rssi)
    }

    private fun sessionJson(session: BluetoothSessionInfo): JsonObject = buildJsonObject {
        put("sessionId", session.sessionId)
        put("session_id", session.sessionId)
        put("address", session.address)
        put("mode", session.mode)
    }

    private fun transferJson(transfer: BluetoothTransfer): JsonObject = buildJsonObject {
        put("sessionId", transfer.sessionId)
        put("bytesWritten", transfer.bytesWritten)
    }

    private fun readJson(result: BluetoothReadResult): JsonObject = buildJsonObject {
        put("sessionId", result.sessionId)
        put("bytesRead", result.bytesRead)
        if (result.text == null) put("text", JsonNull) else put("text", result.text)
        if (result.dataBase64 == null) {
            put("dataBase64", JsonNull)
        } else {
            put("dataBase64", result.dataBase64)
        }
    }

    private fun serviceJson(service: BleServiceInfo): JsonObject = buildJsonObject {
        put("uuid", service.uuid)
        put("characteristics", buildJsonArray {
            service.characteristics.forEach { add(characteristicJson(it)) }
        })
    }

    private fun characteristicJson(info: BleCharacteristicInfo): JsonObject = buildJsonObject {
        put("uuid", info.uuid)
        put("properties", stringArray(info.properties))
    }

    private fun notificationsJson(info: BleNotificationsInfo): JsonObject = buildJsonObject {
        put("sessionId", info.sessionId)
        put("totalCount", info.notifications.size)
        put("notifications", buildJsonArray {
            info.notifications.forEach { entry ->
                add(buildJsonObject {
                    put("characteristicUuid", entry.characteristicUuid)
                    put("bytesRead", entry.bytesRead)
                    if (entry.text == null) put("text", JsonNull) else put("text", entry.text)
                    if (entry.dataBase64 == null) {
                        put("dataBase64", JsonNull)
                    } else {
                        put("dataBase64", entry.dataBase64)
                    }
                    put("timestamp", entry.timestamp)
                })
            }
        })
    }

    // ---------------- 参数 ----------------

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    /** 与 [text] 不同：数字也收（Operit 的 JS 把 duration_ms 之类传成字符串，但插件可能直接传 number）。 */
    private fun JsonObject.long(vararg keys: String): Long? {
        keys.forEach { key ->
            (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toLongOrNull()?.let { return it }
        }
        return null
    }

    private fun JsonObject.int(vararg keys: String): Int? {
        keys.forEach { key ->
            (this[key] as? JsonPrimitive)?.intOrNull?.let { return it }
            (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun JsonObject.bool(vararg keys: String): Boolean? {
        keys.forEach { key ->
            (this[key] as? JsonPrimitive)?.let { primitive ->
                primitive.booleanOrNull?.let { return it }
                // Operit 里 include_ble / enable 有时以 "true"/"false" 字符串传
                when (primitive.contentOrNull?.trim()?.lowercase()) {
                    "true" -> return true
                    "false" -> return false
                }
            }
        }
        return null
    }

    private fun JsonObject.requireText(key: String): String {
        val value = text(key)?.trim()
        if (value.isNullOrEmpty()) throw PluginHostException("PARAM_MISSING", "missing parameter: $key")
        return value
    }

    private fun JsonObject.requireSessionId(): String {
        val value = text("session_id") ?: text("sessionId")
        if (value.isNullOrBlank()) throw PluginHostException("PARAM_MISSING", "missing parameter: session_id")
        return value.trim()
    }

    private fun stringArray(values: List<String>): JsonArray =
        buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }

    private fun ensureActive(pluginId: String) {
        pluginManager.ensureLoaded()
        pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
    }

    private companion object {
        const val DEFAULT_SCAN_MS = 12_000L
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000L
        const val DEFAULT_READ_TIMEOUT_MS = 5_000L
        const val DEFAULT_DISCOVERY_TIMEOUT_MS = 10_000L
        const val DEFAULT_ACCEPT_TIMEOUT_MS = 30_000L
        const val DEFAULT_MAX_BYTES = 4096
        const val DEFAULT_NOTIFICATION_LIMIT = 20
        const val DEFAULT_LISTENER_NAME = "Aharou Bluetooth"
    }
}
