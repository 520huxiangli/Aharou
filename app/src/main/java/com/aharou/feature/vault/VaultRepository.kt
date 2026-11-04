package com.aharou.feature.vault

import android.content.Context
import com.aharou.core.security.KeystoreCipher
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** 凭据名不合法或引用了不存在的名字。 */
class VaultError(message: String) : RuntimeException(message)

/**
 * 凭据库处于只读保护：密文解不开时内存里只有空表（换机、清数据、Keystore 失效），
 * 任何写入都是整份覆盖，会把原有凭据全部抹掉，因此一律拒绝并上抛由调用方提示。
 *
 * @param reason 解密失败原因，只用于日志与提示的补充说明。
 */
class VaultReadOnlyException(val reason: String) :
    IllegalStateException("凭据库暂时不可读（$reason）：请先处理凭据库再试")

/**
 * 通用凭据库：内存里是一份 name → value 映射，落盘时整份 JSON 用 [KeystoreCipher]
 * （Android Keystore / AES-GCM）加密后写 SharedPreferences——密钥留在 Keystore，永不落盘。
 * 相比 nanoMuse 把 Fernet 密钥文件与密文放在同一目录，这里直拷数据目录也拿不到可解的密钥。
 *
 * 模型只应看到占位符 `{{vault:NAME}}`：由接受密钥（acceptsSecrets）的连接器在用时解析；
 * 工具结果与日志经 [redact] 打码。名字约束 `[A-Za-z0-9_.-]+`。
 */
@Singleton
class VaultRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Any()
    private var cache: Map<String, String>? = null

    /** 解密失败原因（null = 正常）。读盘只在首次进内存时发生一次，与 git 凭据仓库同口径。 */
    private var readFailure: String? = null

    init {
        // 暴露脱敏入口给非 DI 组件（FileLogger / DecisionAuditLog 注入不到本类）。
        VaultRedactor.delegate = ::redact
    }

    companion object {
        private const val TAG = "VaultRepository"
        private const val PREFS = "aharou_vault"
        private const val KEY_BLOB = "blob"

        /** 名字规则：字母、数字、下划线、点、短横线。 */
        val NAME_REGEX = Regex("^[A-Za-z0-9_.-]+$")

        /** 占位符语法 `{{vault:NAME}}`，花括号内允许空白。 */
        val PLACEHOLDER_REGEX = Regex("""\{\{\s*vault:([A-Za-z0-9_.-]+)\s*\}\}""")

        /** 短于该长度的值不当凭据处理：8 位以下多是普通词与变量名，掩掉只会误伤代码与输出。 */
        private const val REDACT_MIN_CHARS = 8

        /** 达到该长度的值一律脱敏：这么长的串按普通词误伤的几率极低，宁可多掩一层。 */
        private const val REDACT_ALWAYS_CHARS = 20

        /**
         * 纯函数：值是否「长得像凭据」，只有像才参与脱敏。
         *
         * 规则按长度分三档：
         * - <8：不脱敏。`dongwu` 这类普通词、短变量名都在这一档；
         * - ≥20：一律脱敏。长到这个量级的东西几乎不可能是自然语言词；
         * - 8~19：必须同时包含大写字母与（数字或符号）。真密钥基本都有这两类字符，
         *   而纯小写词（`dongwu`）、下划线标识符（`attached_equip`）、驼峰标识符
         *   （`HistoryPhase`）恰好各自缺一项，于是不会被误伤。
         *
         * 代价是「8~19 位但没有大写」（如全小写十六进制）的密钥不达标不入脱敏，
         * 这属于为了不误伤代码而接受的风险：这类值本身也极容易被当成普通词。
         */
        fun looksLikeCredential(value: String): Boolean {
            if (value.length < REDACT_MIN_CHARS) return false
            if (value.length >= REDACT_ALWAYS_CHARS) return true
            val hasUpper = value.any { it.isUpperCase() }
            val hasDigitOrSymbol = value.any { it.isDigit() || (!it.isLetterOrDigit() && !it.isWhitespace()) }
            return hasUpper && hasDigitOrSymbol
        }
    }

    /** 全部名字（有序）。 */
    fun names(): List<String> = synchronized(lock) { load().keys.sorted() }

    fun get(name: String): String? = synchronized(lock) { load()[name] }

    fun set(name: String, value: String) {
        if (!NAME_REGEX.matches(name)) {
            throw VaultError("凭据名只允许字母、数字、下划线、点与短横线：$name")
        }
        synchronized(lock) {
            ensureWritable("保存")
            save(load() + (name to value))
        }
    }

    fun delete(name: String): Boolean = synchronized(lock) {
        // 先 load 再查可写：首次进内存时才做解密，顺序反过来的话只读标记还没置上，
        // 「没这条」会先返回 false，界面点删除看起来毫无反应
        val current = load()
        ensureWritable("删除")
        if (name !in current) return@synchronized false
        save(current - name)
        true
    }

    /** 是否含 `{{vault:NAME}}` 占位符；value 可为 String、JsonElement 或 Map，字符串化后统一检索。 */
    fun hasPlaceholders(value: Any?): Boolean {
        if (value == null) return false
        val text = if (value is String) value else value.toString()
        return PLACEHOLDER_REGEX.containsMatchIn(text)
    }

    /** 解析字符串里的占位符；[strict] 为真且名字不在库中时抛 [VaultError]，否则原样保留该占位符。 */
    fun resolve(value: String, strict: Boolean = true): String =
        PLACEHOLDER_REGEX.replace(value) { match ->
            val secret = get(match.groupValues[1])
            if (secret != null) secret
            else {
                // 解密失败时库是空表，不先挑明就会把「暂时读不到」说成「凭据不在库中」，
                // 模型会以为用户没配而反复要求重填。没占位符的字符串不受影响，不走这里。
                readFailure?.let { failure -> throw VaultReadOnlyException(failure) }
                if (strict) throw VaultError("凭据「${match.groupValues[1]}」不在凭据库中") else match.value
            }
        }

    /** 递归解析 JSON 里的占位符（str/dict/list 对应 JsonPrimitive/JsonObject/JsonArray）。 */
    fun resolveElement(element: JsonElement, strict: Boolean = true): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { resolveElement(it.value, strict) })
        is JsonArray -> JsonArray(element.map { resolveElement(it, strict) })
        is JsonPrimitive -> if (element.isString) JsonPrimitive(resolve(element.content, strict)) else element
    }

    /** 递归解析工具参数（dict）。 */
    fun resolveMap(args: Map<String, JsonElement>, strict: Boolean = true): Map<String, JsonElement> =
        args.mapValues { resolveElement(it.value, strict) }

    /**
     * 把文本里看起来像凭据的库内值替换为 `[REDACTED:NAME]`。命中条件见 [looksLikeCredential]。
     * 替换顺序仍是长值优先（见 [redactText]），一个值包含另一个时才不会留下半截。
     */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        val secrets = synchronized(lock) { load().map { (name, value) -> name to value } }
        return redactText(text, secrets)
    }

    /**
     * 读内存缓存；未命中则解盘（只解一次，之后的读写都走缓存）。
     *
     * 「本来就是空库」与「解密失败」必须分开：前者是正常的空表，后者只是「暂时读不到」，
     * 此时若允许写入，一次新增凭据就会把原有全部凭据覆盖掉。故失败时置只读标记，
     * 把错误留给写入口拒绝；读取照旧返回空表，不让设置页与日志脱敏跟着崩。
     *
     * 不做重试：redact 会随每条日志与工具结果被调用，反复解密失败会让每次调用都去做一次
     * Keystore 运算；密钥不可恢复的场景居多，重启 App 重读即可。
     */
    private fun load(): Map<String, String> {
        cache?.let { return it }
        val raw = prefs.getString(KEY_BLOB, null)
        val data: Map<String, String> = if (raw.isNullOrEmpty()) {
            emptyMap()
        } else {
            runCatching {
                val json = JSONObject(KeystoreCipher.decryptString(raw))
                json.keys().asSequence().associateWith { json.optString(it) }
            }.getOrElse { e ->
                val reason = e.message ?: e.javaClass.simpleName
                readFailure = reason
                FileLogger.w(TAG, "凭据库暂时不可读（$reason）：读取按空表返回，写入已拒绝，避免整份覆盖其余凭据")
                emptyMap()
            }
        }
        cache = data
        return data
    }

    /** 写入口的统一闸门：读不到就不许写，否则内存里的空表会成为新的全量内容。 */
    private fun ensureWritable(action: String) {
        val failure = readFailure ?: return
        FileLogger.w(TAG, "拒绝${action}凭据：凭据库暂时不可读（$failure），写入已拒绝")
        throw VaultReadOnlyException(failure)
    }

    /**
     * 丢弃本机凭据库重来：清掉密文与只读标记、cache，下一次写入按空库重建。
     *
     * 只读标记是进程级的，不提供出口的话换机 / 清数据后整个进程都无法再保存任何凭据；
     * 代价是清掉后原有凭据确实拿不回来了——调用方必须二次确认。
     */
    fun reset() = synchronized(lock) {
        prefs.edit().remove(KEY_BLOB).apply()
        cache = emptyMap()
        readFailure = null
        FileLogger.w(TAG, "凭据库已被手动清空，后续写入按空库重建")
    }

    private fun save(data: Map<String, String>) {
        ensureWritable("写入")
        val json = JSONObject()
        data.forEach { (name, value) -> json.put(name, value) }
        prefs.edit().putString(KEY_BLOB, KeystoreCipher.encryptString(json.toString())).apply()
        cache = data
    }
}

/**
 * 纯函数：把 [secrets] 里「看起来像凭据」的值按 `[REDACTED:NAME]` 替掉。
 *
 * 按值长度降序替换（一个值包含另一个时，长的那段先被换掉，短值就不会留下半截）；
 * 是否算凭据由 [VaultRepository.looksLikeCredential] 判定。与 Android 无关，便于单测。
 */
internal fun redactText(text: String, secrets: List<Pair<String, String>>): String {
    if (text.isEmpty()) return text
    val ordered = secrets
        .filter { it.second.isNotEmpty() && VaultRepository.looksLikeCredential(it.second) }
        .sortedByDescending { it.second.length }
    var result = text
    for ((name, secret) in ordered) {
        if (secret in result) result = result.replace(secret, "[REDACTED:$name]")
    }
    return result
}
