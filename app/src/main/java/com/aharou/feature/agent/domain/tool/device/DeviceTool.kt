package com.aharou.feature.agent.domain.tool.device

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.personal.DeviceReadCategory
import com.aharou.feature.agent.domain.personal.DeviceReadQuota
import com.aharou.feature.agent.domain.personal.DeviceReadQuotaDecision
import com.aharou.feature.agent.domain.personal.PersonalAccessText
import com.aharou.feature.agent.domain.personal.PersonalCapability
import com.aharou.feature.agent.domain.personal.PersonalContextAccess
import com.aharou.feature.agent.domain.workflow.AgentTurnTrigger
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 设备能力工具：让 Agent 用上手机本体（闹钟、日历、联系人、剪贴板、定位、天气、媒体、设备状态、相册）。
 *
 * 与 [com.aharou.feature.agent.domain.tool.a11y.A11yTool] / vscreen 同构——一个工具带 action 分发，
 * 而不是每个能力单独占一个工具位：设备能力之间参数高度重叠，拆开只会撑大每轮的 tools 数组。
 *
 * 读类动作免确认，写类动作（建闹钟、写日历/联系人、写剪贴板、控制媒体、发或回复通知）由 [effectiveCapabilities]
 * 升到 [ToolCapability.MODIFY_DEVICE_STATE]，交给权限策略引擎裁决。
 */
@Singleton
class DeviceTool @Inject constructor(
    private val alarms: AlarmHandler,
    private val clipboard: ClipboardHandler,
    private val deviceInfo: DeviceInfoHandler,
    private val photos: PhotosHandler,
    private val calendar: CalendarHandler,
    private val contacts: ContactsHandler,
    private val location: LocationHandler,
    private val weather: WeatherHandler,
    private val media: MediaHandler,
    private val notification: NotificationHandler,
    private val sms: SmsSendHandler,
    private val personalAccess: PersonalContextAccess,
    private val personalText: PersonalAccessText,
    private val readQuota: DeviceReadQuota,
) : AgentTool() {

    private companion object {
        const val TAG = "DeviceTool"

        /** 会改变设备状态的动作：只有它们这次调用才算「改设备」。 */
        val WRITE_ACTIONS = setOf(
            "alarm", "timer", "clipboard_write", "calendar_add", "contacts_add", "media_control",
            "notification_send", "notification_reply", "sms_send",
        )

        val ACTIONS = listOf(
            "alarm", "timer",
            "calendar_list", "calendar_add",
            "contacts_search", "contacts_add",
            "clipboard_read", "clipboard_write",
            "location", "weather", "media_control",
            "notification_read", "notification_send", "notification_reply",
            "device_info", "photos_recent",
            "sms_preview", "sms_send", "sms_results",
        )

        val TIME_PATTERNS = listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")
    }

    override val name = "device"

    override val description =
        "操作手机本体（免 root、免 Shizuku）。action 取值：" +
            "alarm=建系统闹钟（hour 0-23、minutes 0-59，可选 message）——建出来的闹钟与用户手设的等价，杀进程照样响；" +
            "timer=倒计时（seconds）；" +
            "calendar_list=看日历事件（days 可选，默认今天起 1 天）；" +
            "calendar_add=建日历事件（title、begin「yyyy-MM-dd HH:mm」，可选 end / duration_minutes 默认 60、location、description）；" +
            "contacts_search=查联系人（query 可选，按名字或号码模糊匹配）；contacts_add=建联系人（name，另给 phone 或 email）；" +
            "clipboard_read=读剪贴板（要求本应用在前台）；clipboard_write=写剪贴板（text）；" +
            "location=当前位置（坐标 + 尽量反查地址）；weather=天气（city 可选，不给就用当前位置）；" +
            "media_control=控制正在播放的媒体（media_action：play / pause / toggle / next / previous / stop / volume_up / volume_down / mute）；" +
            "notification_read=读最近的通知（limit 默认 20，可选 package 只看某个应用；需要用户先开「通知使用权」）；" +
            "notification_send=发一条通知提醒用户（title、text）；" +
            "notification_reply=直接在通知里回复（key 取自 notification_read，text 为回复内容；只有 can_reply=true 的通知能回）；" +
            "device_info=机型/系统/电量/存储/内存/网络快照；" +
            "photos_recent=相册最近图片的元数据（limit 默认 20、最多 200，只给文件名与路径，不给图片本体）；" +
            "sms_preview=发短信前必须先预览（phone、text）：给出字符数、按运营商口径估算的条数与 5 分钟内有效的一次性 token，" +
            "并提示可能产生的费用，这一步不会发出任何短信；" +
            "sms_send=真正发出短信（phone、text、token）：token 必须来自号码与正文完全一致的 sms_preview，用完即失效，没预览过会被直接拒绝；" +
            "sms_results=查最近的短信发送记录（limit 默认 10、最多 50）：status 是「交给运营商没有」，" +
            "delivery_status 是「运营商确认收件人收到没有」，后者依赖运营商状态报告，缺省停在 pending，不要拿它断言没送到。" +
            "读类动作免确认；建闹钟、写日历联系人、写剪贴板、控制媒体、发通知、回复通知、发短信按写操作处理。" +
            "短信发出去就撤不回：务必先预览、把号码与正文念给用户确认，再发。" +
            "权限不足时错误里带 capabilityId 与「去系统设置哪里开」，不要自动重试；" +
            "想知道各能力当前能不能用（含短信/通话记录/应用使用统计），先调 personal_context(action=capabilities)。"

    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE

    override val capabilities = setOf(ToolCapability.READ_DEVICE_DATA)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            "action", ParameterType.STRING,
            "操作：" + ACTIONS.joinToString(" / "),
            true,
            enum = ACTIONS,
        ),
        "hour" to ToolParameter("hour", ParameterType.INTEGER, "alarm 专用：小时 0-23", false),
        "minutes" to ToolParameter("minutes", ParameterType.INTEGER, "alarm 专用：分钟 0-59", false),
        "seconds" to ToolParameter("seconds", ParameterType.INTEGER, "timer 专用：倒计时秒数", false),
        "message" to ToolParameter("message", ParameterType.STRING, "alarm / timer 专用：闹钟或计时器的标签", false),
        "skip_ui" to ToolParameter(
            "skip_ui", ParameterType.BOOLEAN,
            "alarm / timer 专用：true 让系统时钟直接建好、不弹确认界面（默认 true）", false,
        ),
        "days" to ToolParameter("days", ParameterType.INTEGER, "calendar_list 专用：往后看几天，默认 1（今天）", false),
        "title" to ToolParameter("title", ParameterType.STRING, "calendar_add 专用：事件标题", false),
        "begin" to ToolParameter("begin", ParameterType.STRING, "calendar_add 专用：开始时间，格式 yyyy-MM-dd HH:mm", false),
        "end" to ToolParameter("end", ParameterType.STRING, "calendar_add 专用：结束时间（与 duration_minutes 二选一）", false),
        "duration_minutes" to ToolParameter("duration_minutes", ParameterType.INTEGER, "calendar_add 专用：时长分钟，默认 60", false),
        "location" to ToolParameter("location", ParameterType.STRING, "calendar_add 专用：地点", false),
        "description" to ToolParameter("description", ParameterType.STRING, "calendar_add 专用：备注", false),
        "query" to ToolParameter("query", ParameterType.STRING, "contacts_search 专用：名字或号码片段，留空列出前几条", false),
        "name" to ToolParameter("name", ParameterType.STRING, "contacts_add 专用：联系人姓名", false),
        "phone" to ToolParameter(
            "phone", ParameterType.STRING,
            "contacts_add：联系人电话号码；sms_preview / sms_send：收信人号码（可带 + 与国际区号）", false,
        ),
        "email" to ToolParameter("email", ParameterType.STRING, "contacts_add 专用：邮箱", false),
        "text" to ToolParameter(
            "text", ParameterType.STRING,
            "clipboard_write：要写入剪贴板的文本；notification_send：通知正文；notification_reply：要发出的回复内容；" +
                "sms_preview / sms_send：短信正文（发送时必须与预览时完全一致）",
            false,
        ),
        "token" to ToolParameter(
            "token", ParameterType.STRING,
            "sms_send 专用：sms_preview 返回的一次性 token，5 分钟内有效，且必须与 phone / text 完全对应", false,
        ),
        "key" to ToolParameter(
            "key", ParameterType.STRING,
            "notification_reply 专用：notification_read 返回的那条通知的 key", false,
        ),
        "city" to ToolParameter("city", ParameterType.STRING, "weather 专用：城市名，留空用当前位置", false),
        "package" to ToolParameter("package", ParameterType.STRING, "notification_read 专用：只看这个包名的应用，留空看全部", false),
        "media_action" to ToolParameter(
            "media_action", ParameterType.STRING,
            "media_control 专用：play / pause / toggle / next / previous / stop / volume_up / volume_down / mute",
            false,
            enum = listOf("play", "pause", "toggle", "next", "previous", "stop", "volume_up", "volume_down", "mute"),
        ),
        "limit" to ToolParameter("limit", ParameterType.INTEGER, "photos_recent / contacts_search / sms_results 专用：返回条数", false),
    )

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> =
        if (action(args) in WRITE_ACTIONS) setOf(ToolCapability.MODIFY_DEVICE_STATE) else capabilities

    /**
     * 后台自动触发的轮次里，读位置与通知要先过配额。用户手打的轮次不受约束，
     * 所以判定依据是 [AgentContext.turnTrigger] 而不是「有没有配额配置」。
     */
    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext,
    ): ToolResult {
        if (context.turnTrigger == AgentTurnTrigger.SCHEDULED_TASK) {
            val act = action(args)
            val category = when (act) {
                "location" -> DeviceReadCategory.LOCATION
                // weather 不给城市时会自己走定位，与直接读位置是同一笔消耗。
                "weather" -> if (args.str("city").isNullOrBlank()) DeviceReadCategory.LOCATION else null
                "notification_read" -> DeviceReadCategory.NOTIFICATION
                else -> null
            }
            if (category != null) {
                val decision = readQuota.checkAndRecord(category)
                if (!decision.allowed) return quotaExceeded(decision)
            }
        }
        return execute(args)
    }

    private fun quotaExceeded(decision: DeviceReadQuotaDecision): ToolResult {
        val what = when (decision.category) {
            DeviceReadCategory.LOCATION -> "位置"
            DeviceReadCategory.NOTIFICATION -> "通知"
            DeviceReadCategory.SMS -> "短信"
        }
        val why = when (decision.reason) {
            DeviceReadQuota.REASON_TOO_FREQUENT -> "距上一次读取太近"
            else -> "24 小时内已读 ${decision.used} 次，达到 ${decision.limit} 次上限"
        }
        return ToolResult.Error(
            "后台自动读取$what 的配额已用尽：$why。约 ${decision.retryAfterSeconds} 秒后可再读。" +
                "这次是自动化任务发起的运行，用户不在跟前；不要改用别的 action 绕开，" +
                "也不要连续重试。把「需要用户手动确认才能继续读取$what」写进结果交给用户。",
            "DEVICE_READ_QUOTA_EXCEEDED",
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val act = action(args)
            ?: return ToolResult.Error("缺少 action（${ACTIONS.joinToString(" / ")}）", "MISSING_ACTION")

        val result: ToolResult = try {
            when (act) {
                "alarm" -> {
                    val hour = args["hour"]?.jsonPrimitive?.intOrNull
                    val minutes = args["minutes"]?.jsonPrimitive?.intOrNull
                    if (hour == null || minutes == null) {
                        ToolResult.Error("alarm 需要 hour 与 minutes", "MISSING_TIME")
                    } else {
                        alarms.setAlarm(hour, minutes, args.str("message"), args.flag("skip_ui", true))
                    }
                }

                "timer" -> {
                    val seconds = args["seconds"]?.jsonPrimitive?.intOrNull
                    if (seconds == null) {
                        ToolResult.Error("timer 需要 seconds", "MISSING_DURATION")
                    } else {
                        alarms.setTimer(seconds, args.str("message"), args.flag("skip_ui", true))
                    }
                }

                "calendar_list" -> {
                    val days = (args["days"]?.jsonPrimitive?.intOrNull ?: 1).coerceIn(1, 60)
                    val start = startOfToday()
                    calendar.list(start, start + days * 24L * 60 * 60 * 1000, args["limit"]?.jsonPrimitive?.intOrNull)
                }

                "calendar_add" -> {
                    val title = args.str("title")
                    val begin = parseTime(args.str("begin"))
                    when {
                        title == null -> ToolResult.Error("calendar_add 需要 title", "MISSING_TITLE")
                        begin == null -> ToolResult.Error(
                            "calendar_add 需要 begin，格式 yyyy-MM-dd HH:mm（例如 2026-10-19 09:30）",
                            "MISSING_BEGIN",
                        )
                        else -> {
                            val end = parseTime(args.str("end"))
                                ?: (begin + (args["duration_minutes"]?.jsonPrimitive?.intOrNull ?: 60) * 60_000L)
                            calendar.add(title, begin, end, args.str("location"), args.str("description"))
                        }
                    }
                }

                "contacts_search" -> contacts.search(args.str("query"), args["limit"]?.jsonPrimitive?.intOrNull)

                "contacts_add" -> {
                    val name = args.str("name")
                    if (name == null) {
                        ToolResult.Error("contacts_add 需要 name", "MISSING_NAME")
                    } else {
                        contacts.add(name, args.str("phone"), args.str("email"))
                    }
                }

                "clipboard_read" -> clipboard.read()

                "clipboard_write" -> {
                    val text = args.str("text")
                    if (text.isNullOrEmpty()) {
                        ToolResult.Error("clipboard_write 需要非空 text", "MISSING_TEXT")
                    } else {
                        clipboard.write(text)
                    }
                }

                "location" -> location.current()

                "weather" -> weather.today(args.str("city"))

                "media_control" -> {
                    val mediaAction = args.str("media_action")
                    if (mediaAction == null) {
                        ToolResult.Error("media_control 需要 media_action", "MISSING_MEDIA_ACTION")
                    } else {
                        media.control(mediaAction.lowercase())
                    }
                }

                "notification_read" -> notification.read(
                    args["limit"]?.jsonPrimitive?.intOrNull,
                    args.str("package"),
                )

                "notification_send" -> {
                    val title = args.str("title")
                    val text = args.str("text")
                    if (title == null || text == null) {
                        ToolResult.Error("notification_send 需要 title 与 text", "MISSING_NOTIFICATION_TEXT")
                    } else {
                        notification.send(title, text)
                    }
                }

                "notification_reply" -> {
                    val key = args.str("key")
                    val text = args.str("text")
                    if (key == null || text == null) {
                        ToolResult.Error("notification_reply 需要 key 与 text", "MISSING_REPLY_ARGS")
                    } else {
                        notification.reply(key, text)
                    }
                }

                "sms_preview" -> {
                    val phone = args.str("phone")
                    val text = args.str("text")
                    when {
                        phone == null -> ToolResult.Error("sms_preview 需要 phone", "MISSING_PHONE")
                        text == null -> ToolResult.Error("sms_preview 需要非空 text", "MISSING_TEXT")
                        else -> when (val preview = sms.preview(phone, text)) {
                            is SmsSendHandler.PreviewResult.Rejected ->
                                ToolResult.Error(preview.message, preview.code)
                            is SmsSendHandler.PreviewResult.Ready -> ToolResult.Success(buildJsonObject {
                                put("phone", preview.phone)
                                put("text", preview.text)
                                put("chars", preview.chars)
                                put("segments", preview.segments)
                                put("token", preview.token)
                                put("token_valid_seconds", preview.validSeconds)
                                put("note", preview.costNote)
                            })
                        }
                    }
                }

                "sms_send" -> {
                    val phone = args.str("phone")
                    val text = args.str("text")
                    val token = args.str("token")
                    when {
                        phone == null -> ToolResult.Error("sms_send 需要 phone", "MISSING_PHONE")
                        text == null -> ToolResult.Error(
                            "sms_send 需要非空 text，且必须与预览时一字不差",
                            "MISSING_TEXT",
                        )
                        token == null -> ToolResult.Error(
                            "sms_send 需要 token：先 sms_preview 预览一次，再把它返回的 token 原样传回来",
                            "SMS_PREVIEW_REQUIRED",
                        )
                        else -> when (val sent = sms.send(phone, text, token)) {
                            is SmsSendHandler.SendResult.Rejected -> ToolResult.Error(sent.message, sent.code)
                            is SmsSendHandler.SendResult.Submitted -> ToolResult.Success(buildJsonObject {
                                put("record_id", sent.recordId)
                                put("phone", sent.phone)
                                put("segments", sent.segments)
                                put("status", "pending")
                                put(
                                    "note",
                                    "已交给系统短信服务，回执还没回来。过几秒用 sms_results 查这条 record_id 的真实结果，" +
                                        "再告诉用户成没成；不要因为这里没报错就说「已送达」。",
                                )
                            })
                        }
                    }
                }

                "sms_results" -> {
                    val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 10).coerceIn(1, 50)
                    val records = sms.lastResults(limit)
                    // SimpleDateFormat 不是线程安全的，每次现建一个——工具可能被多个会话并发调用。
                    val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    ToolResult.Success(buildJsonObject {
                        put("count", records.size)
                        if (records.isEmpty()) put("note", "还没有短信发送记录。")
                        if (records.any { it.status == "pending" }) {
                            put(
                                "pending_note",
                                "status=pending 只表示回执还没回来（刚提交、回执未到，或者进程被系统回收时回执丢了），" +
                                    "不等于已送达也不等于失败；不要据此告诉用户「已发送」。",
                            )
                        }
                        if (records.any { it.deliveryStatus == "pending" }) {
                            put(
                                "delivery_note",
                                "delivery_status=pending 表示还没收到运营商的送达回执：可能刚发出、运营商没开状态报告、" +
                                    "或回执晚于本应用进程存活期。status=sent 已说明交给了运营商，这只是补充信息，" +
                                    "不能因为它停在 pending 就说对方没收到。",
                            )
                        }
                        put("records", buildJsonArray {
                            records.forEach { record ->
                                add(buildJsonObject {
                                    put("record_id", record.id)
                                    put("time", timeFormat.format(Date(record.at)))
                                    put("phone", record.phone)
                                    put("segments", record.segments)
                                    put("status", record.status)
                                    put("delivery_status", record.deliveryStatus)
                                    record.errorCode?.let { put("error_code", it) }
                                    put("text_head", record.textHead)
                                })
                            }
                        })
                    })
                }

                "device_info" -> deviceInfo.snapshot()

                "photos_recent" -> photos.recent(args["limit"]?.jsonPrimitive?.intOrNull, null)

                else -> ToolResult.Error("不支持的 action：$act", "INVALID_ACTION")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "device 执行失败：$act", e)
            ToolResult.Error("操作失败：${e.message}")
        }
        return decorate(act, result)
    }

    /**
     * 权限类失败统一补一句「capabilityId + 状态 + 去系统设置哪里开」：模型照原话转述，用户就能自己开。
     * 只补权限/访问类错误——时间不合法、没有可写日历这类业务错误原样返回，别掺无关指引。
     */
    private fun decorate(action: String, result: ToolResult): ToolResult {
        val error = result as? ToolResult.Error ?: return result
        // 已经是能力目录自己拼好的错误（含 capabilityId 与指引），别再挂一遍。
        if (error.code.startsWith("PERSONAL_ACCESS_")) return result
        val permissionLike = error.code.contains("PERMISSION") ||
            error.code.contains("ACCESS") ||
            error.code == "NOTIFICATIONS_DISABLED"
        if (!permissionLike) return result
        val capability = capabilityFor(action) ?: return result
        return error.copy(message = personalText.appendHint(error.message, personalAccess.snapshot(capability)))
    }

    /** action → 能力目录 id，保证工具错误与 personal_context 的权限总览说的是同一件事。 */
    private fun capabilityFor(action: String): PersonalCapability? = when (action) {
        "alarm", "timer" -> PersonalCapability.ALARMS
        "calendar_list" -> PersonalCapability.CALENDAR_READ
        "calendar_add" -> PersonalCapability.CALENDAR_WRITE
        "contacts_search" -> PersonalCapability.CONTACTS_READ
        "contacts_add" -> PersonalCapability.CONTACTS_WRITE
        "clipboard_read", "clipboard_write" -> PersonalCapability.CLIPBOARD
        "location", "weather" -> PersonalCapability.LOCATION
        "media_control" -> PersonalCapability.MEDIA_CONTROL
        "notification_read", "notification_reply" -> PersonalCapability.NOTIFICATIONS_READ
        "notification_send" -> PersonalCapability.NOTIFICATIONS_POST
        "photos_recent" -> PersonalCapability.PHOTOS
        "sms_preview", "sms_results" -> PersonalCapability.SMS
        "sms_send" -> PersonalCapability.SMS_WRITE
        "device_info" -> PersonalCapability.DEVICE_INFO
        else -> null
    }

    private fun action(args: Map<String, JsonElement>): String? =
        args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private fun Map<String, JsonElement>.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun Map<String, JsonElement>.flag(key: String, default: Boolean): Boolean =
        this[key]?.jsonPrimitive?.booleanOrNull ?: default

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 接受 yyyy-MM-dd HH:mm / yyyy-MM-dd HH:mm:ss / yyyy-MM-dd 三种写法。 */
    private fun parseTime(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        for (pattern in TIME_PATTERNS) {
            val millis = runCatching {
                SimpleDateFormat(pattern, Locale.US)
                    .apply { isLenient = false }
                    .parse(text)
                    ?.time
            }.getOrNull()
            if (millis != null) return millis
        }
        return null
    }
}