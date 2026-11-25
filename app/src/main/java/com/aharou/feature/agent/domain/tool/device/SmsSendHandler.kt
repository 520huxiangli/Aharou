package com.aharou.feature.agent.domain.tool.device

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.personal.PersonalCapability
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 短信发送：预览 → 发送两道，发送回执落盘。
 *
 * 为什么要「先预览拿 token、再凭 token 发」：短信发出去撤不回，还要花用户的钱。
 * 而模型完全可能把上一条消息的号码 / 正文记混，或者干脆没看内容就直接发。
 * 让发送必须先换一个一次性 token（绑定号码 + 正文 + 5 分钟有效期），
 * 就把「模型自己描述的内容」和「用户看到并确认过的内容」锁在一起——
 * 没预览过、或者预览后改了号码或正文，token 都对不上，发送直接被拒。
 *
 * 为什么回执要自己收：`sendMultipartTextMessage` 只是「提交给系统短信服务」，
 * 提交成功不等于送达，运营商侧失败（无信号、空 PDU、被拦截）只体现在 sentIntent 里。
 * 不落盘这些结果，模型就只能报「已发出」，用户却根本没收到。
 *
 * 为什么用 SharedPreferences 而不是 Room：发送记录是纯本地诊断数据，最多 50 条，
 * 不需要查询、不需要和会话/任务关联；为它加一张表就要跟着改 schema 与迁移，
 * 成本远大于收益。正文只留前若干字，完整内容不落明文盘。
 */
@Singleton
class SmsSendHandler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private companion object {
        const val TAG = "SmsSendHandler"

        const val PREFS_NAME = "sms_send_log"
        const val KEY_RECORDS = "records"
        const val MAX_RECORDS = 50

        /** 预览 token 的有效期：够用户看完一条短信再决定，又不至于让旧预览长期可发。 */
        const val PREVIEW_TTL_MS = 5 * 60 * 1000L

        const val ACTION_SMS_SENT = "com.aharou.action.SMS_SENT"
        const val ACTION_SMS_DELIVERED = "com.aharou.action.SMS_DELIVERED"
        const val EXTRA_RECORD_ID = "record_id"
        const val EXTRA_PART_INDEX = "part_index"

        /**
         * 送达状态。与 [STATUS_*] 是两层：
         * STATUS_* 是「交给运营商没有」，[DELIVERY_*] 是「运营商确认收件人收到没有」。
         * 运营商不开状态报告、或回执晚到、或进程被回收，都会停在 [DELIVERY_PENDING]，
         * 这不能当失败，也不能当送达。
         */
        const val DELIVERY_UNKNOWN = "unknown"
        const val DELIVERY_PENDING = "pending"
        const val DELIVERY_DELIVERED = "delivered"
        const val DELIVERY_PARTIAL = "partial"
        const val DELIVERY_FAILED = "failed"

        /** 某一段还没收到回执。用极值而不是 0：0 是系统的 RESULT_RECEIVED 之类合法码，会混淆。 */
        const val PART_UNKNOWN = Int.MIN_VALUE

        const val TEXT_HEAD_CHARS = 30

        const val STATUS_PENDING = "pending"
        const val STATUS_SENT = "sent"
        const val STATUS_PARTIAL = "partial"
        const val STATUS_FAILED = "failed"

        /** GSM-7 单条上限，超过就按 UCS-2 的 70 字一条重算。 */
        const val SEGMENT_CHARS_ASCII = 160
        const val SEGMENT_CHARS_UNICODE = 70
    }

    /** 一次预览的产物：模型拿 token 去调 sms_send，用户看的是同一份号码与正文。 */
    sealed class PreviewResult {
        data class Ready(
            val phone: String,
            val text: String,
            val chars: Int,
            val segments: Int,
            val token: String,
            val validSeconds: Long,
            /** 给模型读的原文（含费用提示），不是 UI 文案。 */
            val costNote: String,
        ) : PreviewResult()

        data class Rejected(val code: String, val message: String) : PreviewResult()
    }

    sealed class SendResult {
        /** 已提交给系统短信服务；真实结果靠 [lastResults] 里的回执确认。 */
        data class Submitted(val recordId: String, val phone: String, val segments: Int) : SendResult()

        data class Rejected(val code: String, val message: String) : SendResult()
    }

    private data class Preview(val phone: String, val text: String, val createdAt: Long)

    private val previews = ConcurrentHashMap<String, Preview>()

    private val requestCodes = AtomicInteger(1000)

    // SecureRandom 首次初始化在某些 ROM 上要几十毫秒，不要每条记录都新建一个。
    private val random by lazy { SecureRandom() }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 本能力在能力目录里的 id，让 DeviceTool 的错误挂到统一的「去哪开」指引上。 */
    fun capability(): PersonalCapability = PersonalCapability.SMS_WRITE

    /** SEND_SMS 是否已授予。只查不申请——授权入口必须在 Activity 层，这里弹不出框。 */
    fun canSend(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED

    fun preview(phoneRaw: String, text: String): PreviewResult {
        val phone = normalizePhone(phoneRaw)
        if (phone.none { it.isDigit() }) {
            return PreviewResult.Rejected(
                "SMS_INVALID_PHONE",
                "phone 里没有数字：「${phoneRaw.take(24)}」不是可用号码。请传真实号码（可带 + 与国际区号）。",
            )
        }
        if (text.isBlank()) {
            return PreviewResult.Rejected("SMS_EMPTY_TEXT", "正文是空的，没什么可发。")
        }

        val now = System.currentTimeMillis()
        pruneExpired(now)
        val token = tokenFor(phone, text, now)
        previews[token] = Preview(phone = phone, text = text, createdAt = now)

        // 计费口径按 ASCII 160 / 非 ASCII 70 估，与运营商对单条短信的经典口径一致；
        // 但长短信拼接时每段还要占 6 字节用户数据头，实际每段约 153 / 67 字，
        // 所以这里给的是「至少这么多条」的量级提示，不能当计费承诺。
        val ascii = text.all { it.code in 32..126 }
        val perSegment = if (ascii) SEGMENT_CHARS_ASCII else SEGMENT_CHARS_UNICODE
        val chars = text.length
        val segments = ((chars + perSegment - 1) / perSegment).coerceAtLeast(1)

        return PreviewResult.Ready(
            phone = phone,
            text = text,
            chars = chars,
            segments = segments,
            token = token,
            validSeconds = PREVIEW_TTL_MS / 1000,
            costNote = "这条短信约 $segments 条（正文 $chars 字，" +
                (if (ascii) "纯 ASCII 按 $SEGMENT_CHARS_ASCII 字/条估" else "含非 ASCII 字符按 $SEGMENT_CHARS_UNICODE 字/条估") +
                "，长短信拼接另占头开销，实际以运营商计费为准）。" +
                "发送可能产生运营商费用，发出后无法撤回。" +
                "确认号码与正文都没问题后，用 token 调 sms_send；改了号码或正文要重新 sms_preview。",
        )
    }

    fun send(phoneRaw: String, text: String, token: String): SendResult {
        val phone = normalizePhone(phoneRaw)
        val now = System.currentTimeMillis()
        val preview = previews[token.trim()]

        // token 必须和「这一次要发的号码 + 正文」一字不差：用户确认的是预览里那份内容，
        // 拿同一条 token 去发别的内容，等于绕过了确认。
        if (preview == null ||
            preview.phone != phone ||
            preview.text != text ||
            now - preview.createdAt > PREVIEW_TTL_MS
        ) {
            previews.remove(token.trim())
            return SendResult.Rejected(
                "SMS_PREVIEW_REQUIRED",
                "这次发送没有对应的有效预览：token 不存在、已过 5 分钟，或者 phone / text 与预览时不一致。" +
                    "先调 sms_preview 把号码与正文原样预览一遍，再拿它返回的 token 发；不要复用旧 token 反复重试。",
            )
        }

        if (!canSend()) {
            return SendResult.Rejected(
                "SMS_PERMISSION_DENIED",
                "没有短信发送权限（SEND_SMS 未被授予），这次没有发出任何短信。" +
                    "请用户到「设置 → 应用 → Aharou → 权限 → 短信」打开后再发；应用不会自己弹授权框，也不要连续重试。",
            )
        }

        // 一次性消费：同一份预览只能发一次，避免模型重试把同一条短信发两遍。
        previews.remove(token.trim())

        val smsManager = smsManager()
            ?: return SendResult.Rejected(
                "SMS_UNAVAILABLE",
                "这台设备上没有可用的短信服务（可能是纯 Wi-Fi 设备或被系统策略禁用），发不了短信。",
            )
        val parts = runCatching { smsManager.divideMessage(text) }.getOrNull()
        if (parts.isNullOrEmpty()) {
            return SendResult.Rejected("SMS_EMPTY_TEXT", "系统没能把正文切成短信段（正文为空或不可编码）。")
        }

        val recordId = newRecordId()
        val segments = parts.size
        appendRecord(
            SendRecord(
                id = recordId,
                at = now,
                phone = phone,
                textHead = if (text.length <= TEXT_HEAD_CHARS) text else text.take(TEXT_HEAD_CHARS) + "…",
                segments = segments,
                status = STATUS_PENDING,
                errorCode = null,
                partResults = List(segments) { PART_UNKNOWN },
                deliveryStatus = DELIVERY_PENDING,
                deliveryResults = List(segments) { PART_UNKNOWN },
            ),
        )

        val sentIntents = ArrayList<PendingIntent>(segments)
        parts.indices.forEach { index -> sentIntents += sentPendingIntent(recordId, index) }
        // 送达回执：单独一组 intent。它是「运营商确认收件人收到」的信号，比 sentIntent 晚、
        // 也更靠不住（运营商不开状态报告、或回执晚于进程存活期时都不会来），所以只能当补充信息。
        val deliveryIntents = ArrayList<PendingIntent>(segments)
        parts.indices.forEach { index -> deliveryIntents += deliveryPendingIntent(recordId, index) }

        return try {
            @Suppress("DEPRECATION")
            smsManager.sendMultipartTextMessage(phone, null, parts, sentIntents, deliveryIntents)
            SendResult.Submitted(recordId = recordId, phone = phone, segments = segments)
        } catch (e: Exception) {
            FileLogger.e(TAG, "短信提交失败", e)
            updateRecord(recordId) { record ->
                // 根本没发出去，送达回执不会来，置 unknown 而不是 pending，免得看起来像「还在等」。
                record.copy(status = STATUS_FAILED, errorCode = "SUBMIT_FAILED", deliveryStatus = DELIVERY_UNKNOWN)
            }
            SendResult.Rejected(
                "SMS_SEND_FAILED",
                "短信没有提交出去：${e.message}。可能是权限被系统收回或短信服务不可用；" +
                    "不要把这次当成功告诉用户，改好再重新 sms_preview。",
            )
        }
    }

    fun lastResults(limit: Int): List<SendRecord> =
        readRecords().sortedByDescending { it.at }.take(limit.coerceIn(1, MAX_RECORDS))

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager? = runCatching { SmsManager.getDefault() }.getOrNull()

    /**
     * 手机号归一化：用户与模型给号码的写法五花八门（空格、横线、括号），
     * 但 token 校验要求「预览的号码」与「发送的号码」是同一个，所以两边都先归一。
     */
    private fun normalizePhone(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (raw.trim().startsWith("+")) "+$digits" else digits
    }

    /** 号码 + 正文 + 生成时刻 取 SHA-256 前 16 位十六进制：模型猜不到，也不泄露内容。 */
    private fun tokenFor(phone: String, text: String, at: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$phone\u0000$text\u0000$at".toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun newRecordId(): String {
        val bytes = ByteArray(8).also { random.nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun pruneExpired(now: Long) {
        previews.entries.removeIf { now - it.value.createdAt > PREVIEW_TTL_MS }
    }

    private fun sentPendingIntent(recordId: String, partIndex: Int): PendingIntent {
        val intent = Intent(ACTION_SMS_SENT).apply {
            // 限定包名：回执只回本应用，不广播给别的监听者。
            setPackage(context.packageName)
            putExtra(EXTRA_RECORD_ID, recordId)
            putExtra(EXTRA_PART_INDEX, partIndex)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCodes.incrementAndGet(),
            intent,
            // IMMUTABLE：系统短信进程只需按原样投递，不需要改内容；高版本也强制要求显式指定可变性。
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** 送达回执的 PendingIntent：与 sentIntent 同一套 recordId + 段号，只是 action 不同。 */
    private fun deliveryPendingIntent(recordId: String, partIndex: Int): PendingIntent {
        val intent = Intent(ACTION_SMS_DELIVERED).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_RECORD_ID, recordId)
            putExtra(EXTRA_PART_INDEX, partIndex)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCodes.incrementAndGet(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * 回执接收器。只能动态注册：本项目不给短信能力加 manifest 声明（加一条静态 receiver 就要
     * 改 manifest，也会让系统在空闲时拉起进程）。代价是进程被回收期间没收到的回执会丢，
     * 那条记录就停在 pending——这一点在 sms_results 的文案里讲清了。
     *
     * 送达回执比发送回执更吃这一点：运营商状态报告可能几十秒后才到，那时进程未必还在。
     * 所以「delivered」只能当加成，收不到 [ACTION_SMS_DELIVERED] 不代表没送到。
     *
     * 用 RECEIVER_EXPORTED 而不是 NOT_EXPORTED：回执由系统电话进程发出，不是本应用自己发的
     * 广播，NOT_EXPORTED 在部分版本上会被直接丢弃。为防止别的应用伪造「已发送」，
     * onReceive 只认带已知 record_id 且还有段没收完回执的记录——record_id 是 64 位随机值。
     */
    private val receiptReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            val recordId = intent.getStringExtra(EXTRA_RECORD_ID) ?: return
            val partIndex = intent.getIntExtra(EXTRA_PART_INDEX, -1)
            if (partIndex < 0) return
            when (intent.action) {
                ACTION_SMS_SENT -> onPartResult(recordId, partIndex, resultCode)
                ACTION_SMS_DELIVERED -> onDeliveryResult(recordId, partIndex, resultCode)
            }
        }
    }

    init {
        runCatching {
            ContextCompat.registerReceiver(
                context,
                receiptReceiver,
                IntentFilter().apply {
                    addAction(ACTION_SMS_SENT)
                    addAction(ACTION_SMS_DELIVERED)
                },
                ContextCompat.RECEIVER_EXPORTED,
            )
        }.onFailure { FileLogger.e(TAG, "短信回执接收器注册失败", it) }
    }

    private fun onDeliveryResult(recordId: String, partIndex: Int, code: Int) {
        updateRecord(recordId) { record -> applyDeliveryResult(record, partIndex, code) }
    }

    /**
     * 送达状态的聚合口径与发送层一致：所有段都有回执才下结论，否则保持 pending。
     * 与发送层分开存，是因为两层的结论可能不一致——已经交给运营商、但收件人手机没收到。
     */
    private fun applyDeliveryResult(record: SendRecord, partIndex: Int, code: Int): SendRecord {
        if (partIndex < 0 || partIndex >= record.segments) return record
        val results = record.deliveryResults.toMutableList()
        while (results.size < record.segments) results.add(PART_UNKNOWN)
        results[partIndex] = code
        val settled = results.filter { it != PART_UNKNOWN }
        val okCount = settled.count { it == Activity.RESULT_OK }
        val status = when {
            settled.size < record.segments -> DELIVERY_PENDING
            okCount == settled.size -> DELIVERY_DELIVERED
            okCount == 0 -> DELIVERY_FAILED
            else -> DELIVERY_PARTIAL
        }
        return record.copy(deliveryResults = results, deliveryStatus = status)
    }

    private fun onPartResult(recordId: String, partIndex: Int, code: Int) {
        updateRecord(recordId) { record -> applyPartResult(record, partIndex, code) }
    }

    /**
     * 多段短信的回执是逐段回来的：任一段失败整条就算不完整。
     * 只有全部段都有结果才下结论，否则保持 pending——提前说「已发送」比不说更误导。
     */
    private fun applyPartResult(record: SendRecord, partIndex: Int, code: Int): SendRecord {
        if (partIndex !in record.partResults.indices) return record
        val results = record.partResults.toMutableList().apply { this[partIndex] = code }
        val settled = results.filter { it != PART_UNKNOWN }
        val okCount = settled.count { it == Activity.RESULT_OK }
        val failureCount = settled.size - okCount
        val status = when {
            settled.size < results.size -> STATUS_PENDING
            failureCount == 0 -> STATUS_SENT
            okCount == 0 -> STATUS_FAILED
            else -> STATUS_PARTIAL
        }
        val errorCode = if (failureCount == 0) {
            null
        } else {
            results.firstOrNull { it != PART_UNKNOWN && it != Activity.RESULT_OK }?.let(::resultCodeName)
        }
        return record.copy(partResults = results, status = status, errorCode = errorCode)
    }

    private fun resultCodeName(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "GENERIC_FAILURE"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "RADIO_OFF"
        SmsManager.RESULT_ERROR_NULL_PDU -> "NULL_PDU"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "NO_SERVICE"
        SmsManager.RESULT_ERROR_FDN_CHECK_FAILURE -> "FDN_CHECK_FAILURE"
        else -> "RESULT_$code"
    }

    private fun readRecords(): List<SendRecord> =
        runCatching {
            prefs.getString(KEY_RECORDS, null)?.let { json.decodeFromString<List<SendRecord>>(it) }
        }.getOrNull().orEmpty()

    private fun writeRecords(records: List<SendRecord>) {
        runCatching {
            prefs.edit().putString(KEY_RECORDS, json.encodeToString(records.take(MAX_RECORDS))).apply()
        }.onFailure { FileLogger.e(TAG, "写短信发送记录失败", it) }
    }

    private fun appendRecord(record: SendRecord) {
        writeRecords(listOf(record) + readRecords())
    }

    private fun updateRecord(id: String, transform: (SendRecord) -> SendRecord) {
        val records = readRecords()
        if (records.none { it.id == id }) return
        writeRecords(records.map { if (it.id == id) transform(it) else it })
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
}

/**
 * 一条短信发送留档。
 *
 * [status] 是机器可读的英文态：pending / sent / partial / failed；
 * [partResults] 存每段回执的原始结果码（[Int.MIN_VALUE] 表示该段还没回），
 * 既用于当下聚合状态，也方便日后排查「哪一段被运营商拒了」。
 * 正文只留 [textHead]：完整内容可能是验证码、私人对话，不值得为了日志留在明文盘上。
 */
@Serializable
data class SendRecord(
    val id: String,
    val at: Long,
    val phone: String,
    val textHead: String = "",
    val segments: Int,
    val status: String,
    val errorCode: String? = null,
    val partResults: List<Int> = emptyList(),
    /** 送达层状态：unknown / pending / delivered / partial / failed（旧记录若没有按 unknown 读）。 */
    val deliveryStatus: String = "unknown",
    /** 每段送达回执的原始结果码；[Int.MIN_VALUE] 表示该段还没回。 */
    val deliveryResults: List<Int> = emptyList(),
)
