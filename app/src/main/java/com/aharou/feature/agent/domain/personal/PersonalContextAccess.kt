package com.aharou.feature.agent.domain.personal

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 个人数据能力的唯一状态出口：每个能力返回 status / scope / reason / settingsRoute。
 *
 * 三条自我约束：
 * 1. 只查状态，不读数据——[snapshot] 不做任何 ContentResolver 查询，只问 PackageManager /
 *    AppOpsManager / NotificationManager 这些元信息接口，所以可以在工具失败的回调路径上直接调。
 * 2. 判断不确定就说「不确定」：ROM 不开放的限制检查（[getWhitelistedRestrictedPermissions] 抛
 *    SecurityException）返回 null 而不是 false，避免把「查不了」误报成「用户拒绝了」。
 * 3. 从不申请权限：本类没有 Activity，也不发起任何 requestPermissions——「去哪开」只通过
 *    [PersonalAccessSnapshot.settingsRoute] 交给上层写成文案。
 */
@Singleton
class PersonalContextAccess @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private companion object {
        const val TAG = "PersonalContextAccess"

        // 各数据源的 provider authority。calendar / contacts 用公开常量有过 deprecated 争议，
        // 这里直接写死字符串，判断的是「ROM 有没有装这个 provider」。
        const val CALENDAR_AUTHORITY = "com.android.calendar"
        const val CONTACTS_AUTHORITY = "com.android.contacts"
        const val SMS_AUTHORITY = "sms"
        const val CALL_LOG_AUTHORITY = "call_log"
    }

    /** 目录全量快照（顺序与 [PersonalCapability] 声明一致，便于设置页与能力总览对照）。 */
    fun all(): List<PersonalAccessSnapshot> = PersonalCapability.values().map { snapshot(it) }

    /** 单个能力状态。任何检查异常都降级为 unavailable/status_unavailable，绝不抛给调用方。 */
    fun snapshot(capability: PersonalCapability): PersonalAccessSnapshot =
        try {
            when (capability) {
                PersonalCapability.ALARMS -> granted(capability, reason = PersonalAccessReason.NO_PERMISSION_REQUIRED, scope = listOf("system_clock"))
                PersonalCapability.CALENDAR_READ -> permissionState(
                    capability,
                    listOf(Manifest.permission.READ_CALENDAR),
                    authority = CALENDAR_AUTHORITY,
                )
                PersonalCapability.CALENDAR_WRITE -> permissionState(
                    capability,
                    listOf(Manifest.permission.WRITE_CALENDAR),
                    authority = CALENDAR_AUTHORITY,
                )
                PersonalCapability.CONTACTS_READ -> permissionState(
                    capability,
                    listOf(Manifest.permission.READ_CONTACTS),
                    authority = CONTACTS_AUTHORITY,
                )
                PersonalCapability.CONTACTS_WRITE -> permissionState(
                    capability,
                    listOf(Manifest.permission.WRITE_CONTACTS),
                    authority = CONTACTS_AUTHORITY,
                )
                PersonalCapability.CLIPBOARD -> granted(
                    capability,
                    reason = PersonalAccessReason.REQUIRES_FOREGROUND,
                    scope = listOf("foreground_only"),
                )
                PersonalCapability.LOCATION -> locationState()
                PersonalCapability.WEATHER -> granted(
                    capability,
                    reason = PersonalAccessReason.NO_PERMISSION_REQUIRED,
                    scope = listOf("network", "city_or_location"),
                )
                PersonalCapability.MEDIA_CONTROL -> granted(
                    capability,
                    reason = PersonalAccessReason.NO_PERMISSION_REQUIRED,
                    scope = listOf("media_session"),
                )
                PersonalCapability.NOTIFICATIONS_READ -> notificationReadState()
                PersonalCapability.NOTIFICATIONS_POST -> notificationPostState()
                PersonalCapability.PHOTOS -> photosState()
                PersonalCapability.DEVICE_INFO -> granted(
                    capability,
                    reason = PersonalAccessReason.NO_PERMISSION_REQUIRED,
                    scope = listOf("system_snapshot"),
                )
                PersonalCapability.SMS -> permissionState(
                    capability,
                    listOf(Manifest.permission.READ_SMS),
                    authority = SMS_AUTHORITY,
                )
                PersonalCapability.CALL_LOG -> permissionState(
                    capability,
                    listOf(Manifest.permission.READ_CALL_LOG),
                    authority = CALL_LOG_AUTHORITY,
                )
                PersonalCapability.APP_USAGE -> appUsageState()
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "读取能力状态失败：${capability.id}", e)
            finish(capability, PersonalAccessStatus.UNAVAILABLE, reason = PersonalAccessReason.CHECK_FAILED)
        }

    private fun locationState(): PersonalAccessSnapshot = when {
        granted(Manifest.permission.ACCESS_FINE_LOCATION) -> finish(
            PersonalCapability.LOCATION,
            PersonalAccessStatus.GRANTED,
            scope = listOf("precise_location"),
            permissions = listOf(Manifest.permission.ACCESS_FINE_LOCATION),
        )
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) -> finish(
            PersonalCapability.LOCATION,
            PersonalAccessStatus.PARTIAL,
            scope = listOf("approximate_location"),
            reason = PersonalAccessReason.APPROXIMATE_LOCATION_ONLY,
            permissions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
        )
        revokedByPolicy(Manifest.permission.ACCESS_FINE_LOCATION) ||
            revokedByPolicy(Manifest.permission.ACCESS_COARSE_LOCATION) -> finish(
            PersonalCapability.LOCATION,
            PersonalAccessStatus.RESTRICTED,
            reason = PersonalAccessReason.RESTRICTED_BY_POLICY,
        )
        else -> finish(
            PersonalCapability.LOCATION,
            PersonalAccessStatus.DENIED,
            reason = PersonalAccessReason.PERMISSION_NOT_GRANTED,
        )
    }

    /**
     * 通知使用权：应用无法自行申请，只能由用户在系统里勾选。
     * 勾了但监听服务没连上（被系统杀掉）属于能用但不完整，这里仍报 granted——缓存为空由
     * NotificationHandler 自己的 note 说明，不在这里猜。
     */
    private fun notificationReadState(): PersonalAccessSnapshot {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)
        return if (enabled) {
            finish(
                PersonalCapability.NOTIFICATIONS_READ,
                PersonalAccessStatus.GRANTED,
                scope = listOf("in_memory_history"),
            )
        } else {
            finish(
                PersonalCapability.NOTIFICATIONS_READ,
                PersonalAccessStatus.DENIED,
                reason = PersonalAccessReason.NOTIFICATION_LISTENER_DISABLED,
            )
        }
    }

    private fun notificationPostState(): PersonalAccessSnapshot {
        val channelEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            granted(Manifest.permission.POST_NOTIFICATIONS)
        return when {
            channelEnabled && runtimeGranted -> finish(
                PersonalCapability.NOTIFICATIONS_POST,
                PersonalAccessStatus.GRANTED,
                scope = listOf("post_notifications"),
            )
            !channelEnabled -> finish(
                PersonalCapability.NOTIFICATIONS_POST,
                PersonalAccessStatus.DENIED,
                reason = PersonalAccessReason.NOTIFICATIONS_DISABLED,
            )
            else -> finish(
                PersonalCapability.NOTIFICATIONS_POST,
                PersonalAccessStatus.DENIED,
                reason = PersonalAccessReason.PERMISSION_NOT_GRANTED,
                permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
            )
        }
    }

    /**
     * 相册：Android 13+ 是细粒度媒体权限，14+ 还可能出现「只选了部分照片」；
     * 「所有文件访问」是老路径，也直接够用。
     */
    private fun photosState(): PersonalAccessSnapshot {
        val allFiles = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()
        if (allFiles) {
            return finish(PersonalCapability.PHOTOS, PersonalAccessStatus.GRANTED, scope = listOf("all_files"))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (granted(Manifest.permission.READ_MEDIA_IMAGES)) {
                return finish(
                    PersonalCapability.PHOTOS,
                    PersonalAccessStatus.GRANTED,
                    scope = listOf("images"),
                    permissions = listOf(Manifest.permission.READ_MEDIA_IMAGES),
                )
            }
            val selectedOnly = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            if (selectedOnly) {
                return finish(
                    PersonalCapability.PHOTOS,
                    PersonalAccessStatus.PARTIAL,
                    scope = listOf("selected_photos"),
                    reason = PersonalAccessReason.SELECTED_PHOTOS_ONLY,
                    permissions = listOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
                )
            }
            return if (revokedByPolicy(Manifest.permission.READ_MEDIA_IMAGES)) {
                finish(
                    PersonalCapability.PHOTOS,
                    PersonalAccessStatus.RESTRICTED,
                    reason = PersonalAccessReason.RESTRICTED_BY_POLICY,
                    permissions = listOf(Manifest.permission.READ_MEDIA_IMAGES),
                )
            } else {
                finish(
                    PersonalCapability.PHOTOS,
                    PersonalAccessStatus.DENIED,
                    reason = PersonalAccessReason.PERMISSION_NOT_GRANTED,
                    permissions = listOf(Manifest.permission.READ_MEDIA_IMAGES),
                )
            }
        }
        return permissionState(PersonalCapability.PHOTOS, listOf(Manifest.permission.READ_EXTERNAL_STORAGE))
    }

    /** 使用情况访问是 AppOps 特殊权限：普通授权框弹不出来，只能看 AppOps 的判定。 */
    private fun appUsageState(): PersonalAccessSnapshot =
        if (usageAccessAllowed()) {
            finish(
                PersonalCapability.APP_USAGE,
                PersonalAccessStatus.GRANTED,
                scope = listOf("usage_stats"),
            )
        } else {
            finish(
                PersonalCapability.APP_USAGE,
                PersonalAccessStatus.DENIED,
                reason = PersonalAccessReason.USAGE_ACCESS_NOT_ALLOWED,
            )
        }

    /** 通用「运行时权限」判定：provider 缺失 → unavailable；全部授予 → granted；否则 denied/restricted。 */
    private fun permissionState(
        capability: PersonalCapability,
        permissions: List<String>,
        authority: String? = null,
    ): PersonalAccessSnapshot {
        if (authority != null && !providerAvailable(authority)) {
            return finish(
                capability,
                PersonalAccessStatus.UNAVAILABLE,
                reason = PersonalAccessReason.PROVIDER_UNAVAILABLE,
                permissions = permissions,
            )
        }
        val allowed = permissions.filter(::granted)
        if (permissions.isNotEmpty() && allowed.size == permissions.size) {
            return finish(
                capability,
                PersonalAccessStatus.GRANTED,
                scope = permissions.map(::scopeName),
                permissions = permissions,
            )
        }
        val restricted = permissions.any(::revokedByPolicy) || installerRestricted(permissions)
        return if (restricted) {
            finish(
                capability,
                PersonalAccessStatus.RESTRICTED,
                scope = allowed.map(::scopeName),
                reason = PersonalAccessReason.RESTRICTED_BY_POLICY,
                permissions = permissions,
            )
        } else {
            finish(
                capability,
                PersonalAccessStatus.DENIED,
                scope = allowed.map(::scopeName),
                reason = PersonalAccessReason.PERMISSION_NOT_GRANTED,
                permissions = permissions,
            )
        }
    }

    private fun finish(
        capability: PersonalCapability,
        status: PersonalAccessStatus,
        scope: List<String> = emptyList(),
        reason: String? = null,
        permissions: List<String> = emptyList(),
    ): PersonalAccessSnapshot = PersonalAccessSnapshot(
        capability = capability,
        status = status,
        scope = scope,
        reason = reason,
        permissions = permissions,
        settingsRoute = routeFor(capability, status),
    )

    private fun granted(
        capability: PersonalCapability,
        reason: String?,
        scope: List<String>,
    ): PersonalAccessSnapshot = finish(capability, PersonalAccessStatus.GRANTED, scope = scope, reason = reason)

    /** 状态不可用时给出「去哪开」：特殊权限指向各自的系统页，其余统一指应用权限页。 */
    private fun routeFor(capability: PersonalCapability, status: PersonalAccessStatus): String? = when {
        status == PersonalAccessStatus.GRANTED -> null
        status == PersonalAccessStatus.UNAVAILABLE -> null
        capability == PersonalCapability.APP_USAGE -> PersonalSettingsRoute.USAGE_ACCESS
        capability == PersonalCapability.NOTIFICATIONS_READ -> PersonalSettingsRoute.NOTIFICATION_LISTENER
        else -> PersonalSettingsRoute.APP_PERMISSIONS
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** 系统 / 设备管理策略把权限锁死（与「用户没给」是两件事，后者用户自己能开）。 */
    private fun revokedByPolicy(permission: String): Boolean =
        runCatching {
            context.packageManager.isPermissionRevokedByPolicy(permission, context.packageName)
        }.getOrDefault(false)

    /**
     * Android 10 起 READ_SMS / READ_CALL_LOG 属受限权限，安装来源（应用商店、企业策略）可以把它
     * 从白名单里摘掉——这种状态下用户在权限页也打不开，必须明确告知而不是让他反复尝试。
     *
     * 查询本身在部分 ROM 上直接抛 SecurityException：此时返回 false，表示「查不了」，不当作被限制。
     */
    private fun installerRestricted(permissions: List<String>): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val sensitive = permissions.any {
            it == Manifest.permission.READ_SMS || it == Manifest.permission.READ_CALL_LOG
        }
        if (!sensitive) return false
        val allowlist = runCatching {
            context.packageManager.getWhitelistedRestrictedPermissions(
                context.packageName,
                PackageManager.FLAG_PERMISSION_WHITELIST_INSTALLER or
                    PackageManager.FLAG_PERMISSION_WHITELIST_SYSTEM or
                    PackageManager.FLAG_PERMISSION_WHITELIST_UPGRADE,
            )
        }.getOrNull() ?: return false
        return permissions.any { it !in allowlist }
    }

    /**
     * provider 是否存在。用 resolveContentProvider 而不是真的查询：判断「ROM 有没有这个数据源」
     * 不该顺带读一次数据，也不该触发权限异常。
     */
    private fun providerAvailable(authority: String): Boolean =
        runCatching { context.packageManager.resolveContentProvider(authority, 0) != null }.getOrDefault(false)

    // AppOpsManager 的这两个查询都被标了 deprecated，但「使用情况访问」的状态至今只能靠它们问，
    // 平台没有给出替代 API。
    @Suppress("DEPRECATION")
    private fun usageAccessAllowed(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** scope 用固定词组，便于模型与设置页按词匹配，不写自然语言。 */
    private fun scopeName(permission: String): String = when (permission) {
        Manifest.permission.READ_SMS -> "read_sms"
        Manifest.permission.READ_CALL_LOG -> "read_call_log"
        Manifest.permission.READ_CONTACTS -> "read_contacts"
        Manifest.permission.WRITE_CONTACTS -> "write_contacts"
        Manifest.permission.READ_CALENDAR -> "read_calendar"
        Manifest.permission.WRITE_CALENDAR -> "write_calendar"
        Manifest.permission.ACCESS_FINE_LOCATION -> "precise_location"
        Manifest.permission.ACCESS_COARSE_LOCATION -> "approximate_location"
        Manifest.permission.READ_MEDIA_IMAGES -> "images"
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED -> "selected_photos"
        Manifest.permission.READ_EXTERNAL_STORAGE -> "shared_storage"
        Manifest.permission.POST_NOTIFICATIONS -> "post_notifications"
        else -> permission.substringAfterLast('.')
    }
}
