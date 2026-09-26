package com.aharou.feature.settings.data.repository

import android.content.Context
import com.aharou.feature.settings.data.remote.UpdateCheckResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/** 更新通道：稳定版（仅正式版）/ 最新版（含预览版）。 */
enum class UpdateChannel {
    STABLE, LATEST
}

/**
 * 「自动检查更新」偏好存储：开关、更新通道、上次检测日期统一存 SharedPreferences
 * （键值文件，非数据库）。上次检测日期不写数据库，但也不放 cacheDir——缓存目录可能被
 * 系统清理导致「每天一次」失效，持久化到 SharedPreferences 更可靠。
 */
@Singleton
class UpdateCheckSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("update_check_prefs", Context.MODE_PRIVATE)

    /** 自动检查更新开关，默认开启。 */
    var autoCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }

    /** 更新通道，默认稳定版。 */
    var channel: UpdateChannel
        get() = prefs.getString(KEY_CHANNEL, null)
            ?.let { runCatching { UpdateChannel.valueOf(it) }.getOrNull() }
            ?: UpdateChannel.STABLE
        set(value) {
            prefs.edit().putString(KEY_CHANNEL, value.name).apply()
        }

    /** 今天是否已检测过（按记录日期判断）。 */
    fun hasCheckedToday(): Boolean = prefs.getString(KEY_LAST_CHECKED, null) == today()

    /** 记录今天已检测。 */
    fun markCheckedToday() {
        prefs.edit().putString(KEY_LAST_CHECKED, today()).apply()
    }

    /**
     * 把版本与更新信息写入 `~/.aicode/update-info.json`（宿主 filesDir/aicode/），
     * 供容器内 AI 读取（当前版本、更新通道、最近检查时间、最新版本与逐版本更新日志）。
     * 写入失败静默，不影响检测流程。
     */
    fun writeUpdateInfo(currentVersion: String, channel: UpdateChannel, result: UpdateCheckResult) {
        runCatching {
            val dir = File(context.filesDir, "aicode").apply { mkdirs() }
            val obj = JSONObject()
            obj.put("currentVersion", currentVersion)
            obj.put("channel", channel.name.lowercase())
            obj.put("lastCheckedAt", java.time.LocalDateTime.now().toString())
            when (result) {
                is UpdateCheckResult.UpToDate -> obj.put("hasUpdate", false)
                is UpdateCheckResult.NewVersion -> {
                    obj.put("hasUpdate", true)
                    obj.put("latestVersion", result.info.latestTag)
                    val updates = org.json.JSONArray()
                    result.info.updates.forEach { u ->
                        updates.put(
                            JSONObject().apply {
                                put("tag", u.tag)
                                put("changelog", u.changelog)
                            }
                        )
                    }
                    obj.put("updates", updates)
                }
                is UpdateCheckResult.Error -> {
                    obj.put("hasUpdate", false)
                    obj.put("error", result.message)
                }
            }
            File(dir, "update-info.json").writeText(obj.toString())
        }
    }

    private fun today(): String = java.time.LocalDate.now().toString()

    private companion object {
        const val KEY_ENABLED = "auto_check_enabled"
        const val KEY_CHANNEL = "update_channel"
        const val KEY_LAST_CHECKED = "last_checked_date"
    }
}
