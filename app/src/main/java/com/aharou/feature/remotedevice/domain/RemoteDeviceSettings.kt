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
 * 默认不开服务。服务一旦开启，界面上的配对码就是唯一准入门槛（可选用 [allowPublicPeer] 决定
 * 是否接受公网对端），所以其余几项都取保守值：远端动作默认不再逐次审批（[alwaysAskRemote]），
 * 因为协助场景下弹窗没人点——真要不被打断就得连本机工具一起去审批，那是 [enabled] 的职责。
 */
data class RemoteDeviceConfig(
    val enabled: Boolean = false,
    val port: Int = DEFAULT_PORT,
    /** 展示给对端看的设备名；空表示用机型。 */
    val deviceName: String = "",
    /**
     * 远端来源的动作即便被本机策略引擎自动放行（如读文件、白名单只读命令），是否仍升级为一次审批。
     *
     * 默认 false：协助场景下用户往往不在手机跟前，每次弹窗等于把功能废了。
     * 介意的话可以打开，但它只管**远端来源**的动作，本机自己的操作不受它影响。
     */
    val alwaysAskRemote: Boolean = false,
    /**
     * 是否接受来自公网的对端。
     *
     * 关闭时只放行私网/回环/链路本地地址（即同一局域网）；开启后才能接受跨网络对端
     * （IPv6 全球单播或经组网工具的地址）。开启后准入只剩配对码，默认关。
     */
    val allowPublicPeer: Boolean = false
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
        val ALLOW_PUBLIC = booleanPreferencesKey("remote_device_allow_public")
    }

    val config: Flow<RemoteDeviceConfig> = context.remoteDeviceDataStore.data.map { prefs ->
        RemoteDeviceConfig(
            enabled = prefs[ENABLED] ?: false,
            port = prefs[PORT] ?: RemoteDeviceConfig.DEFAULT_PORT,
            deviceName = prefs[NAME].orEmpty(),
            alwaysAskRemote = prefs[ALWAYS_ASK] ?: false,
            allowPublicPeer = prefs[ALLOW_PUBLIC] ?: false
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

    suspend fun setAllowPublicPeer(value: Boolean) {
        context.remoteDeviceDataStore.edit { it[ALLOW_PUBLIC] = value }
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
