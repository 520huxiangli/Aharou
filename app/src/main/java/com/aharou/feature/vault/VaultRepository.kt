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

        /** 短于该长度的值不脱敏（1~2 字符不是密钥，掩掉会把普通词也抹掉）。 */
        private const val REDACT_MIN_CHARS = 3

        /** 值达到该长度整串子串替换；更短的只按整 token 替换，避免改写词中片段。 */
        private const val REDACT_FULL_MATCH_CHARS = 6
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
     * 把文本里出现的任何库内值替换为 `[REDACTED:NAME]`。先按值长度降序（一个值包含另一个时不至于留半截）；
     * ≥6 字符整串子串替换，3~5 字符只按整 token 替换（词边界），<3 字符不脱敏。
     */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        val secrets = synchronized(lock) { load().entries.sortedByDescending { it.value.length } }
        var result = text
        for ((name, secret) in secrets) {
            if (secret.length < REDACT_MIN_CHARS) continue
            val mark = "[REDACTED:$name]"
            result = if (secret.length >= REDACT_FULL_MATCH_CHARS) {
                result.replace(secret, mark)
            } else {
                result.replace(Regex("""(?<![\w.+-])${Regex.escape(secret)}(?![\w.+-])"""), mark)
            }
        }
        return result
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
