package com.aharou.feature.plugin.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 蓝牙运行时权限门禁。
 *
 * 能力层不发起权限请求（那是 UI 的职责）：缺权限时返回 `PERMISSION_DENIED` 与缺失清单，
 * 调用方拿清单去申请，重试即可。
 *
 * 两套权限的分界来自系统本身：
 * - Android 12（API 31）起拆成 `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT`，不再需要定位；
 * - Android 11 及以下只有 `BLUETOOTH` / `BLUETOOTH_ADMIN`，且设备发现额外依赖 `ACCESS_FINE_LOCATION`
 *   （定位被关掉时 `startDiscovery` 不会报错，但收不到 `ACTION_FOUND`，排查起来非常费劲）。
 */
internal object BluetoothPermissionGate {

    fun requiredForConnect(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.BLUETOOTH)
        }

    fun requiredForScan(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 扫描结果里的设备名要 BLUETOOTH_CONNECT 才读得到，所以扫描是两个权限一起要。
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

    fun missing(context: Context, required: List<String>): List<String> =
        required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    /** 已授权返回 null，缺权限返回带缺失清单的失败结果。 */
    fun require(context: Context, required: List<String>, action: String): BtResult.Failure? {
        val missing = missing(context, required)
        if (missing.isEmpty()) return null
        return BtResult.Failure(
            code = BtErrorCode.PERMISSION_DENIED,
            message = "$action 需要蓝牙权限，缺失: ${missing.joinToString()}",
            missingPermissions = missing
        )
    }

    fun state(context: Context): BluetoothPermissionState {
        val connect = requiredForConnect()
        val scan = requiredForScan()
        val missing = missing(context, (connect + scan).distinct())
        return BluetoothPermissionState(
            granted = missing.isEmpty(),
            missing = missing,
            sdkInt = Build.VERSION.SDK_INT,
            requiredForConnect = connect,
            requiredForScan = scan
        )
    }
}
