package com.aharou.feature.agent.domain.personal

/**
 * 个人上下文能力目录：Agent 能碰到的每一份个人数据（以及它能改的设备状态）都先在这里登记一个 id。
 *
 * 为什么要有这一层：同一份权限会从三条路被问到——工具执行失败时的错误文案、
 * `personal_context(action=capabilities)` 的权限总览、设置页引导。三处若各写一套判断，
 * 迟早出现「设置页说已授权、工具说没权限」的自相矛盾。这里把 id / 状态口径 / 开启路径收敛成一份，
 * 其余位置只引用 [PersonalCapability] 与 [PersonalAccessSnapshot]。
 *
 * 状态口径见 [PersonalAccessStatus]：权限本身是二元的，但「只有粗定位」「只选了部分照片」
 * 「ROM 拿不到 provider」「安装来源限制」都真实存在，硬压进 granted/denied 会让模型误判该不该调用。
 */
enum class PersonalCapability(val id: String) {
    /** 建闹钟 / 计时器：走系统时钟的标准 intent，不申请任何权限。 */
    ALARMS("alarms"),
    CALENDAR_READ("calendar_read"),
    CALENDAR_WRITE("calendar_write"),
    CONTACTS_READ("contacts_read"),
    CONTACTS_WRITE("contacts_write"),
    CLIPBOARD("clipboard"),
    LOCATION("location"),
    WEATHER("weather"),
    MEDIA_CONTROL("media_control"),

    /** 读通知：靠系统「通知使用权」（特殊权限，只能由用户在系统里勾选）。 */
    NOTIFICATIONS_READ("notifications_read"),

    /** 发通知：运行时权限 POST_NOTIFICATIONS + 应用通知开关。 */
    NOTIFICATIONS_POST("notifications_post"),
    PHOTOS("photos"),
    DEVICE_INFO("device_info"),

    /** 短信库（READ_SMS）。只读，永远不发短信、不改已读状态。 */
    SMS("sms"),

    /** 通话记录（READ_CALL_LOG）。只读，永远不拨号。 */
    CALL_LOG("call_log"),

    /** 应用使用统计（PACKAGE_USAGE_STATS）。特殊权限，只能引导去系统设置手动开。 */
    APP_USAGE("app_usage");

    /**
     * 特殊权限：应用连系统授权框都弹不出来，只能给「去系统设置哪里开」的指路文案。
     * 注意与运行时权限区分——对这些能力调用 requestPermissions 是无效的。
     */
    val requiresManualGrant: Boolean
        get() = this == NOTIFICATIONS_READ || this == APP_USAGE

    companion object {
        fun fromId(id: String): PersonalCapability? =
            values().firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

/** 能力状态。`granted` / `partial` 视为可用，其余一律不调用查询。 */
enum class PersonalAccessStatus(val wire: String) {
    GRANTED("granted"),
    PARTIAL("partial"),
    DENIED("denied"),

    /** 系统或安装来源把权限锁死（国产 ROM、应用商店安装包常见），用户自己在权限页也打不开。 */
    RESTRICTED("restricted"),

    /** 设备上根本没有这个数据服务（ROM 未提供 provider），重试与授权都没用。 */
    UNAVAILABLE("unavailable"),
}

/** 状态附带的原因码：给模型看机器可读的判断依据，不要塞自然语言。 */
object PersonalAccessReason {
    const val NO_PERMISSION_REQUIRED = "no_permission_required"
    const val PERMISSION_NOT_GRANTED = "permission_not_granted"
    const val RESTRICTED_BY_POLICY = "restricted_by_policy"
    const val PROVIDER_UNAVAILABLE = "provider_unavailable"
    const val USAGE_ACCESS_NOT_ALLOWED = "usage_access_not_allowed"
    const val NOTIFICATION_LISTENER_DISABLED = "notification_listener_not_enabled"
    const val NOTIFICATIONS_DISABLED = "notifications_disabled"
    const val APPROXIMATE_LOCATION_ONLY = "approximate_location_only"
    const val SELECTED_PHOTOS_ONLY = "selected_photos_only"
    const val REQUIRES_FOREGROUND = "requires_foreground"
    const val CHECK_FAILED = "status_unavailable"
}

/** 权限不足时该去系统设置里的哪一处开：写进工具结果，模型转述给用户就能照做。 */
object PersonalSettingsRoute {
    /** 设置 → 应用 → Aharou → 权限（运行时权限）。 */
    const val APP_PERMISSIONS = "app_permissions"

    /** 设置 → 应用 → 特殊应用权限 → 使用情况访问。 */
    const val USAGE_ACCESS = "usage_access"

    /** 设置 → 通知 → 通知使用权。 */
    const val NOTIFICATION_LISTENER = "notification_listener"
}

/**
 * 一次能力状态快照。[scope] 是「这次授权实际覆盖到哪些数据」的白名单词组
 * （如 `precise_location`、`selected_photos`、`read_sms`），不写自然语言。
 */
data class PersonalAccessSnapshot(
    val capability: PersonalCapability,
    val status: PersonalAccessStatus,
    val scope: List<String> = emptyList(),
    val reason: String? = null,
    val permissions: List<String> = emptyList(),
    val settingsRoute: String? = null,
) {
    /** 只有 granted / partial 才值得发起查询。 */
    val usable: Boolean
        get() = status == PersonalAccessStatus.GRANTED || status == PersonalAccessStatus.PARTIAL
}
