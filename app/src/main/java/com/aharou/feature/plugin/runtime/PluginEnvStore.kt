package com.aharou.feature.plugin.runtime

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.plugin.domain.PluginHostException
import com.aharou.feature.plugin.domain.PluginManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
private data class EnvFile(
    val schema: Int = ENV_SCHEMA,
    val values: Map<String, String> = emptyMap()
)

private const val ENV_SCHEMA = 1

/**
 * 插件自己的配置存储（`env.*`），与 [PluginSecretStore] 是两套语义：
 *
 * - secrets 是「用户给插件填的凭据」，读取只给值、列表只给键名；
 * - env 是「插件自己记的配置」，列表要连值一起返回，读取支持默认值回落。
 *
 * 落盘仍然复用同一套 Keystore AES-256-GCM 加密：插件配置里常混着 token 或地址，
 * 明文落盘等于把它们交出去给同机其它进程和备份包。键名保持明文，便于排错时对账。
 *
 * 文件独立（`filesDir/plugin-env/<pluginId>.json`），不与 secrets 混在一起：
 * 两者的权限语义不同，混存会让「导出凭据」这类操作把插件自用的配置也一起带出来。
 */
@Singleton
class PluginEnvStore internal constructor(
    private val rootDir: File,
    private val pluginManager: PluginManager,
    private val cipher: PluginSecretCipher
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        pluginManager: PluginManager
    ) : this(File(context.filesDir, ENV_DIR), pluginManager, KeystoreSecretCipher)

    private val writeMutex = Mutex()

    /** 读取一个键；未设置时回落到 [default]，两者都没有则返回空串。 */
    suspend fun get(pluginId: String, key: String, default: String?): JsonElement =
        withContext(Dispatchers.IO) {
            requireActive(pluginId)
            val name = requireKey(key)
            val encrypted = load(fileFor(pluginId))[name]
            val value = when {
                encrypted != null -> cipher.decrypt(encrypted)
                default != null -> default
                else -> ""
            }
            buildJsonObject {
                put("key", name)
                put("value", value)
                put("found", encrypted != null)
            }
        }

    /** 写入一个键；已存在则覆盖。 */
    suspend fun set(pluginId: String, key: String, value: String): JsonElement =
        withContext(Dispatchers.IO) {
            requireActive(pluginId)
            val name = requireKey(key)
            if (value.toByteArray(Charsets.UTF_8).size > MAX_VALUE_BYTES) {
                throw PluginHostException("TOO_LARGE", "env value too large")
            }
            writeMutex.withLock {
                val file = fileFor(pluginId)
                val values = load(file)
                values[name] = cipher.encrypt(value)
                save(file, values)
            }
            buildJsonObject { put("ok", true) }
        }

    /** 列出键值；[prefix] 非空时只返回以它开头的键。 */
    suspend fun list(pluginId: String, prefix: String?): JsonElement = withContext(Dispatchers.IO) {
        requireActive(pluginId)
        val all = load(fileFor(pluginId))
        val filtered = if (prefix.isNullOrEmpty()) all else all.filterKeys { it.startsWith(prefix) }
        val sorted = filtered.keys.sorted()
        buildJsonObject {
            put("values", buildJsonObject {
                sorted.forEach { key ->
                    val encrypted = filtered[key] ?: return@forEach
                    put(key, cipher.decrypt(encrypted))
                }
            })
            put("keys", JsonArray(sorted.map { JsonPrimitive(it) }))
        }
    }

    private fun requireActive(pluginId: String) {
        pluginManager.ensureLoaded()
        pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
    }

    private fun requireKey(key: String): String {
        if (!KEY_PATTERN.matches(key)) throw PluginHostException("PARAM_INVALID", "invalid env key")
        return key
    }

    private fun fileFor(pluginId: String): File = File(rootDir, "$pluginId.json")

    private fun load(file: File): MutableMap<String, String> {
        if (!file.isFile) return mutableMapOf()
        val parsed = try {
            JSON.decodeFromString<EnvFile>(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            FileLogger.e(TAG, "插件配置无法读取或解析，按空处理: ${file.name}", e)
            return mutableMapOf()
        }
        if (parsed.schema != ENV_SCHEMA) {
            FileLogger.w(TAG, "插件配置版本不支持，按空处理: ${file.name}")
            return mutableMapOf()
        }
        return parsed.values.filterKeys { KEY_PATTERN.matches(it) }.toMutableMap()
    }

    private fun save(file: File, values: Map<String, String>) {
        val dir = file.parentFile
            ?: throw PluginHostException("STORAGE_FAILED", "env store directory is unavailable")
        try {
            dir.mkdirs()
            val tmp = File(dir, "${file.name}.tmp")
            tmp.outputStream().use { out ->
                out.write(JSON.encodeToString(EnvFile(values = values)).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            FileLogger.e(TAG, "写入插件配置失败: ${file.name}", e)
            throw PluginHostException("STORAGE_FAILED", "无法写入插件配置")
        }
    }

    private companion object {
        const val TAG = "PluginEnvStore"
        const val ENV_DIR = "plugin-env"
        const val MAX_VALUE_BYTES = 64 * 1024

        /** 比 secrets 的键名宽松一点：插件常按 `API_BASE`、`feature.enable` 这类习惯命名。 */
        val KEY_PATTERN = Regex("^[A-Za-z0-9._-]{1,128}$")
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
