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
        synchronized(lock) { save(load() + (name to value)) }
    }

    fun delete(name: String): Boolean = synchronized(lock) {
        val current = load()
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
            else if (strict) throw VaultError("凭据「${match.groupValues[1]}」不在凭据库中")
            else match.value
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

    private fun load(): Map<String, String> {
        cache?.let { return it }
        val raw = prefs.getString(KEY_BLOB, null)
        val data = if (raw.isNullOrEmpty()) emptyMap() else runCatching {
            val json = JSONObject(KeystoreCipher.decryptString(raw))
            json.keys().asSequence().associateWith { json.optString(it) }
        }.onFailure { FileLogger.w(TAG, "凭据库解密失败: ${it.message}") }.getOrDefault(emptyMap())
        cache = data
        return data
    }

    private fun save(data: Map<String, String>) {
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
