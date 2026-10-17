package com.aharou.feature.agent.domain.schedule.trigger

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 设备状态快照：电量 / 是否充电 / 当前 WiFi 名。
 *
 * 前两项读不到时留 null（表示「无法确认」，条件求值会据此跳过），WiFi 名读不到或没连时为空串——
 * 口径不同是因为前者的未知会影响判定，后者只用于比对。
 */
data class DeviceStateSnapshot(
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val ssid: String = "",
) {
    companion object {

        /** 读一次当前状态；读不到的部分留 null / 空串，不抛异常。 */
        fun read(context: Context): DeviceStateSnapshot {
            val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val percent = runCatching {
                manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
            }.getOrNull()
            val charging = runCatching {
                // 传 null 收件人只取这份粘性广播的最后一份副本，不会真的注册接收器。
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { intent ->
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                }
            }.getOrNull()
            return DeviceStateSnapshot(percent, charging, readSsid(context))
        }

        /**
         * 当前 WiFi 名。没连 WiFi、或系统不给读时返回空串——Android 10 起读 SSID 需要定位权限，
         * 没授权时系统一律返回 `<unknown ssid>`，所以网络类触发要用户授权定位才会生效，
         * 这是系统限制，不是这里的 bug。
         */
        @Suppress("DEPRECATION")
        private fun readSsid(context: Context): String {
            val manager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return ""
            val raw = runCatching { manager.connectionInfo?.ssid }.getOrNull() ?: return ""
            if (raw == WifiManager.UNKNOWN_SSID) return ""
            return raw.trim('"')
        }
    }
}

/**
 * 一次状态比对的结果：当前状态 + 与上次快照相比变了什么。
 *
 * [previous] 为 null 表示首次运行——只记基线，所有变化判定都为 false（没有「上次」可比）。
 * 每项判定都要求两次都读得到：读失败与「真的变了」必须分开，否则一次读失败就会误报事件。
 */
data class DeviceStateCheck(
    val current: DeviceStateSnapshot,
    val previous: DeviceStateSnapshot?,
) {

    // 先把两边拆成可空值，下面各项判定都只看它们：省得每处都跟「可能为空」较劲。
    private val batteryBefore: Int? = previous?.batteryPercent
    private val batteryAfter: Int? = current.batteryPercent
    private val chargingBefore: Boolean? = previous?.charging
    private val chargingAfter: Boolean? = current.charging
    private val ssidBefore: String? = previous?.ssid

    /** 电量数值变了。 */
    val batteryChanged: Boolean = batteryBefore != null && batteryAfter != null && batteryBefore != batteryAfter

    /** 充电状态变了。 */
    val chargingChanged: Boolean = chargingBefore != null && chargingAfter != null && chargingBefore != chargingAfter

    /** 本次插上充电（上次明确没在充、这次明确在充）。 */
    val chargingStarted: Boolean = chargingAfter == true && chargingBefore == false

    /** WiFi 名变了；两次都读得到才算，理由见 [DeviceStateSnapshot.ssid]。 */
    val ssidChanged: Boolean = ssidBefore?.isNotEmpty() == true &&
        current.ssid.isNotEmpty() &&
        ssidBefore != current.ssid

    val hasChanges: Boolean get() = batteryChanged || chargingChanged || ssidChanged

    /**
     * 电量是否在本轮跌破 [threshold]：上次在线上、这次在线下才算。
     * 按「跌破」而不是「低于」判，否则只要一直待在阈值下，每轮检查都会命中一次。
     */
    fun droppedBelow(threshold: Int): Boolean {
        val after = batteryAfter ?: return false
        val before = batteryBefore ?: return false
        return before >= threshold && after < threshold
    }

    /** 一行摘要，日志用。 */
    fun summary(): String {
        val parts = mutableListOf<String>()
        if (batteryChanged) parts += "电量 ${batteryBefore ?: "?"}→${batteryAfter ?: "?"}"
        if (chargingChanged) parts += "充电 $chargingBefore→$chargingAfter"
        if (ssidChanged) parts += "WiFi $ssidBefore→${current.ssid}"
        return parts.joinToString("；")
    }
}

/**
 * 设备状态的比对与留档：读当前状态、跟上次的快照比、把当前状态存成新基线。
 *
 * 只在周期检查（[com.aharou.feature.agent.domain.schedule.ScheduledTaskWorker]）里被调用，
 * 所以状态类事件最迟要等下一轮检查才发现，**最大延迟一个周期（15 分钟）**。
 * 为什么不实时：Android 8 起 `ACTION_BATTERY_CHANGED` / `CONNECTIVITY_ACTION` 不接受静态注册，
 * 想在发生那一刻就知道只能常驻一个前台服务盯着，代价（常驻通知 + 耗电）不划算，已否决——
 * 「晚十几分钟」是设计取舍，不是 bug。
 */
@Singleton
class DeviceStateMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 读当前状态、与上次快照比对，并把当前状态存成新基线。首次运行 [DeviceStateCheck.previous] 为 null。 */
    fun check(): DeviceStateCheck {
        val current = DeviceStateSnapshot.read(context)
        val previous = readBaseline()
        runCatching {
            prefs().edit()
                .putInt(KEY_BATTERY, current.batteryPercent ?: UNKNOWN)
                .putInt(KEY_CHARGING, when (current.charging) {
                    null -> UNKNOWN
                    true -> 1
                    false -> 0
                })
                .putString(KEY_SSID, current.ssid)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply()
        }
        return DeviceStateCheck(current, previous)
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readBaseline(): DeviceStateSnapshot? {
        val prefs = prefs()
        // 用写入时刻当「有没有基线」的判据：电量本身可能读不到（存的是 -1），
        // 单看它分不出「没记过」和「这次读不到」。
        if (!prefs.contains(KEY_UPDATED_AT)) return null
        return DeviceStateSnapshot(
            batteryPercent = prefs.getInt(KEY_BATTERY, UNKNOWN).takeIf { it != UNKNOWN },
            charging = prefs.getInt(KEY_CHARGING, UNKNOWN).let { if (it == UNKNOWN) null else it == 1 },
            ssid = prefs.getString(KEY_SSID, "").orEmpty(),
        )
    }

    private companion object {
        const val PREFS_NAME = "aharou_device_state"
        const val KEY_BATTERY = "battery_percent"
        const val KEY_CHARGING = "charging"
        const val KEY_SSID = "ssid"
        const val KEY_UPDATED_AT = "updated_at"

        /** 电量/充电读不到时的占位值。 */
        const val UNKNOWN = -1
    }
}