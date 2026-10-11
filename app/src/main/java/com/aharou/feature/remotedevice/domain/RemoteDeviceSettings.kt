package com.aharou.feature.remotedevice.domain

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.remoteDeviceDataStore by preferencesDataStore(
    name = "remote_device_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 「跨设备协作」的本机配置。
 *
 * 默认全关：服务不开、端口固定、远端动作一律额外审批（[alwaysAskRemote]）——这是刻意的最严默认，
 * 因为一旦开启，局域网内任何拿到配对 token 的设备都能请求本机执行动作。
 */
data class RemoteDeviceConfig(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
    /** 展示给对端看的设备名；空表示用机型。 */
    val deviceName: String = "",
    /**
     * 远端来源的动作即便被本机策略引擎自动放行（如读文件、白名单只读命令），是否仍升级为一次审批。
     * 默认 true：远端调用与「本机自己在用」不是同一信任级，多问一次更安全。
     */
    val alwaysAskRemote: Boolean = true
) {
    companion object {
        /** 默认端口：避开 debug 变体内置 MCP server 的 47821，两者可同机共存。 */
        const val DEFAULT_PORT = 47822
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
    }
}

@Singleton
class RemoteDeviceSettings @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        /** 私有目录下的子目录：配对文件、token、设备标识都放这里，绝不进工作区。 */
        const val DIR_NAME = "remotedevice"

        private const val TAG = "RemoteDeviceSettings"
        val ENABLED = booleanPreferencesKey("remote_device_enabled")
        val PORT = intPreferencesKey("remote_device_port")
        val NAME = stringPreferencesKey("remote_device_name")
        val ALWAYS_ASK = booleanPreferencesKey("remote_device_always_ask")
    }

    val config: Flow<RemoteDeviceConfig> = context.remoteDeviceDataStore.data.map { prefs ->
        RemoteDeviceConfig(
            enabled = prefs[ENABLED] ?: false,
            port = prefs[PORT] ?: RemoteDeviceConfig.DEFAULT_PORT,
            deviceName = prefs[NAME].orEmpty(),
            alwaysAskRemote = prefs[ALWAYS_ASK] ?: true
        )
    }

    suspend fun current(): RemoteDeviceConfig = config.first()

    suspend fun setEnabled(enabled: Boolean) {
        context.remoteDeviceDataStore.edit { it[ENABLED] = enabled }
    }

    suspend fun setPort(port: Int) {
        context.remoteDeviceDataStore.edit {
            it[PORT] = port.coerceIn(RemoteDeviceConfig.MIN_PORT, RemoteDeviceConfig.MAX_PORT)
        }
    }

    suspend fun setDeviceName(name: String) {
        context.remoteDeviceDataStore.edit { it[NAME] = name.trim() }
    }

    suspend fun setAlwaysAskRemote(value: Boolean) {
        context.remoteDeviceDataStore.edit { it[ALWAYS_ASK] = value }
    }

    /** 对端看到的设备名：用户自定义优先，否则回退机型。 */
    suspend fun displayName(): String = current().deviceName.ifBlank { defaultDeviceName() }

    fun defaultDeviceName(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android" }

    /**
     * 本机安装标识（一次性生成后长期不变）：只用于对端区分多台设备、以及本机授权列表的 key，
     * 不含任何硬件标识（不用 ANDROID_ID / IMEI）。
     */
    suspend fun installId(): String = withContext(Dispatchers.IO) {
        val file = File(File(context.filesDir, DIR_NAME), "device-id.txt")
        runCatching {
            if (file.isFile) {
                file.readText().trim().takeIf { it.isNotBlank() }?.let { return@runCatching it }
            }
            val id = UUID.randomUUID().toString().replace("-", "").take(16)
            file.parentFile?.mkdirs()
            file.writeText(id)
            id
        }.getOrElse {
            FileLogger.w(TAG, "读写本机安装标识失败: ${it.message}")
            "unknown"
        }
    }
}
