package com.aharou.feature.agent.domain.tool.device

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.aharou.feature.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 设备自身状态快照：机型、系统版本、电量、存储、内存、网络。全部免权限。 */
@Singleton
class DeviceInfoHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    fun snapshot(): ToolResult {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val charging = battery?.isCharging

        val dataStat = StatFs(Environment.getDataDirectory().path)
        val memory = ActivityManager.MemoryInfo().also {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }

        return ToolResult.Success(buildJsonObject {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("android", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
            put("abies", Build.SUPPORTED_ABIS.joinToString(", "))
            if (level >= 0) put("battery_percent", level)
            charging?.let { put("charging", it) }
            put("storage_total_gb", "%.1f".format(dataStat.totalBytes / 1e9))
            put("storage_free_gb", "%.1f".format(dataStat.availableBytes / 1e9))
            put("memory_total_gb", "%.1f".format(memory.totalMem / 1e9))
            put("memory_available_gb", "%.1f".format(memory.availMem / 1e9))
            put("low_memory", memory.lowMemory)
            put("network", networkLabel())
        })
    }

    private fun networkLabel(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "未知"
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "离线"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "蜂窝网络"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "其它"
        }
    }
}