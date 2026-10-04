package com.aharou.feature.settings.data.repository

import android.content.Context
import com.aharou.core.security.KeystoreCipher
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 环境变量仓库（自 上游项目 的 EnvVarRepository 移植·适配）。
 *
 * 用户自定义的环境变量，注入到容器内的所有命令与终端进程
 * （典型用途：给脚本/CLI 用的 API Key、EDITOR 等）。
 *
 * 安全：值用 [KeystoreCipher]（Android Keystore / AES-GCM）加密后存
 * SharedPreferences；列表 UI 默认打码。Agent 侧后续只暴露名称、不暴露值。
 */
@Singleton
class EnvVarRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** 单条环境变量。 */
    data class EnvVar(
        val name: String,
        val value: String,
        /** true = 列表里打码显示（默认）。 */
        val secret: Boolean = true,
        /** false = 仅保留名称/值/备注，不再注入容器；旧数据缺该字段时按启用处理。 */
        val enabled: Boolean = true,
        val createdAt: Long = 0L,
    )

    companion object {
        private const val TAG = "EnvVarRepository"
        private const val PREFS = "aharou_env_vars"
        private const val KEY_RECORDS = "records"

        /** 名字规则：合法 shell 变量名。 */
        val NAME_REGEX = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        /** 容器关键变量：命中即拦截（覆盖会破坏 proot 启动/动态链接）。 */
        val RESERVED_NAMES = setOf(
            "PROOT_TMP_DIR", "PROOT_LOADER", "PROOT_LOADER_32", "PROOT_NO_SECCOMP",
            "LD_PRELOAD", "LD_LIBRARY_PATH",
        )
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _entries = MutableStateFlow(loadAll())
    val entries: StateFlow<List<EnvVar>> = _entries.asStateFlow()

    /** 注入用：名称 → 值（解密后）；已停用的变量直接不出现，不注入空值。 */
    fun asMap(): Map<String, String> =
        _entries.value.filter { it.enabled }.associate { it.name to it.value }

    fun upsert(name: String, value: String, secret: Boolean) {
        val list = _entries.value.toMutableList()
        val index = list.indexOfFirst { it.name == name }
        val entry = EnvVar(
            name = name,
            value = value,
            secret = secret,
            // 编辑既有变量时保留启用状态，避免改值就把停用态顺手打开。
            enabled = if (index >= 0) list[index].enabled else true,
            createdAt = if (index >= 0) list[index].createdAt else System.currentTimeMillis(),
        )
        if (index >= 0) list[index] = entry else list.add(entry)
        _entries.value = list
        persist(list)
    }

    /** 启用/停用：停用后保留名称、值与敏感标记，只是不再注入容器。 */
    fun setEnabled(name: String, enabled: Boolean) {
        val list = _entries.value.toMutableList()
        val index = list.indexOfFirst { it.name == name }
        if (index < 0) return
        list[index] = list[index].copy(enabled = enabled)
        _entries.value = list
        persist(list)
    }

    fun remove(name: String) {
        val list = _entries.value.filterNot { it.name == name }
        _entries.value = list
        persist(list)
    }

    private fun persist(list: List<EnvVar>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { entry ->
                arr.put(
                    JSONObject()
                        .put("n", entry.name)
                        .put("v", KeystoreCipher.encryptString(entry.value))
                        .put("s", entry.secret)
                        .put("e", entry.enabled)
                        .put("t", entry.createdAt)
                )
            }
            prefs.edit().putString(KEY_RECORDS, arr.toString()).apply()
        }.onFailure { FileLogger.w(TAG, "persist failed: ${it.message}") }
    }

    private fun loadAll(): List<EnvVar> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = obj.optString("n")
                if (name.isEmpty()) return@mapNotNull null
                EnvVar(
                    name = name,
                    value = runCatching { KeystoreCipher.decryptString(obj.optString("v")) }.getOrDefault(""),
                    secret = obj.optBoolean("s", true),
                    // 旧记录没有 "e" 字段：optBoolean 默认 true，等于保持启用。
                    enabled = obj.optBoolean("e", true),
                    createdAt = obj.optLong("t", 0L),
                )
            }
        }.onFailure { FileLogger.w(TAG, "load failed: ${it.message}") }.getOrDefault(emptyList())
    }
}
