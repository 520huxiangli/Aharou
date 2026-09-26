package com.aharou.feature.settings.presentation.component

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.aharou.core.util.FileLogger

/**
 * 厂商自启动管理跳转：国内 ROM（MIUI/EMUI/ColorOS/OriginOS/Flyme）默认禁止应用自启动，
 * 后台进程与前台服务都会被系统回收。设置页「自启动管理」点击后按厂商跳转对应自启动设置页，
 * intent 在对应 ROM 上不可用时回退到本应用详情页（用户可手动开启后台运行/自启动）。
 */
object OemAutoStartGuide {
    private const val TAG = "OemAutoStartGuide"

    private data class VendorEntry(val component: String, val keywords: List<String>)

    // 各厂商自启动管理页（包名/Activity）。跳转前用 resolveActivity 校验存在性。
    private val vendorEntries = listOf(
        VendorEntry(
            "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            listOf("xiaomi", "redmi")
        ),
        VendorEntry(
            "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            listOf("huawei", "honor")
        ),
        VendorEntry(
            "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
            listOf("oppo", "realme", "oneplus")
        ),
        VendorEntry(
            "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            listOf("vivo", "iqoo")
        ),
        VendorEntry(
            "com.meizu.safe/com.meizu.safe.permission.SmartBGActivity",
            listOf("meizu")
        )
    )

    /** 尝试跳转厂商自启动管理页；无匹配或跳转失败时回退到应用详情页。 */
    fun openAutoStartSettings(context: Context) {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val entry = vendorEntries.firstOrNull { vendor ->
            vendor.keywords.any { manufacturer.contains(it) }
        }
        if (entry != null && startResolved(context, entry.component)) return
        openAppDetails(context)
    }

    private fun startResolved(context: Context, component: String): Boolean {
        val (pkg, cls) = component.split("/", limit = 2)
        val intent = Intent().setComponent(ComponentName(pkg, cls))
        if (intent.resolveActivity(context.packageManager) == null) {
            FileLogger.w(TAG, "自启动设置页不可用: $component")
            return false
        }
        return runCatching { context.startActivity(intent) }
            .onFailure { FileLogger.e(TAG, "跳转自启动设置失败: $component", it) }
            .isSuccess
    }

    /** 跳转到本应用的系统设置详情页（权限页）。供权限提醒弹窗复用。 */
    internal fun openAppDetails(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
        runCatching { context.startActivity(intent) }
            .onFailure { FileLogger.e(TAG, "跳转应用详情页失败", it) }
    }
}