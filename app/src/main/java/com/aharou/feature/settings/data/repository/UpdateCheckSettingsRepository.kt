package com.aharou.feature.settings.data.repository

import android.content.Context
import com.aharou.feature.settings.data.remote.UpdateCheckResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/** 更新通道：正式版（仅正式版）/ 测试版（仅测试包，含 beta 与 RC）。 */
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

    /** 更新通道，默认正式版。 */
    var channel: UpdateChannel
        get() = prefs.getString(KEY_CHANNEL, null)
            ?.let { runCatching { UpdateChannel.valueOf(it) }.getOrNull() }
            ?: UpdateChannel.STABLE
        set(value) {
            prefs.edit().putString(KEY_CHANNEL, value.name).apply()
        }

    /** 自动下载更新包：检测到新版本就在后台下好，用户只需点一下安装。默认开启。 */
    var autoDownloadEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DOWNLOAD, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD, value).apply()
        }

    /**
     * 下载完就自动静默安装（需 Shizuku 授权），默认关闭。
     * 注意：覆盖安装自己会重启 App，且 Android 侧无法回退到上一版。
     */
    var autoInstallEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_INSTALL, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_INSTALL, value).apply()
        }

    /** 已下载好的更新包版本 tag（跨进程重启复用，避免重复下载同一版本）。 */
    var downloadedTag: String?
        get() = prefs.getString(KEY_DOWNLOADED_TAG, null)
        set(value) {
            prefs.edit().putString(KEY_DOWNLOADED_TAG, value).commit()
        }

    /** 已下载好的更新包路径。 */
    var downloadedPath: String?
        get() = prefs.getString(KEY_DOWNLOADED_PATH, null)
        set(value) {
            prefs.edit().putString(KEY_DOWNLOADED_PATH, value).commit()
        }

    /** 今天是否已检测过（按记录日期判断）。 */
    fun hasCheckedToday(): Boolean = prefs.getString(KEY_LAST_CHECKED, null) == today()

    /**
     * 记录今天已检测。用 commit() 同步落盘：apply() 是异步写，进程紧接着被杀（覆盖安装、
     * 冷启动后被系统回收）会丢掉记录，导致同一天反复自动检查、反复弹窗。
     */
    fun markCheckedToday() {
        prefs.edit().putString(KEY_LAST_CHECKED, today()).commit()
    }

    /**
     * 最近一次已提示过的版本 tag。自动检查为同一版本不再重复弹窗（用户点「稍后」后不该被
     * 反复打扰）；手动检查不受此限。
     */
    var lastNotifiedTag: String?
        get() = prefs.getString(KEY_LAST_NOTIFIED_TAG, null)
        set(value) {
            prefs.edit().putString(KEY_LAST_NOTIFIED_TAG, value).commit()
        }

    /**
     * 记录最近一次更新包下载用了哪个源，写到 `~/.aharou/last-update-download.json`。
     *
     * 放这里是因为它同时被挂进容器：出事时 agent（或用户）能直接看到「这次走的 GitCode 还是
     * 回退到了镜像」——否则只能靠猜（下载只链是候选列表，失败会静默换下一个源）。
     */
    fun writeDownloadRecord(tag: String, sourceUrl: String, filePath: String) {
        runCatching {
            val dir = File(context.filesDir, "aharou").apply { mkdirs() }
            val obj = JSONObject().apply {
                put("tag", tag)
                put("sourceUrl", sourceUrl)
                put("fromGitCode", sourceUrl.contains("gitcode.com"))
                put("filePath", filePath)
                put("time", java.time.LocalDateTime.now().toString())
            }
            File(dir, "last-update-download.json").writeText(obj.toString())
        }
    }

    /**
     * 把版本与更新信息写入 `~/.aharou/update-info.json`（宿主 filesDir/aharou/，容器内挂到
     * `/root/.aharou`），供容器内 AI 读取（当前版本、更新通道、最近检查时间、最新版本与逐版本更新日志）。
     * 写入失败静默，不影响检测流程。
     */
    fun writeUpdateInfo(currentVersion: String, channel: UpdateChannel, result: UpdateCheckResult) {
        runCatching {
            val dir = File(context.filesDir, "aharou").apply { mkdirs() }
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
        const val KEY_LAST_NOTIFIED_TAG = "last_notified_tag"
        const val KEY_AUTO_DOWNLOAD = "auto_download_enabled"
        const val KEY_AUTO_INSTALL = "auto_install_enabled"
        const val KEY_DOWNLOADED_TAG = "downloaded_update_tag"
        const val KEY_DOWNLOADED_PATH = "downloaded_update_path"
    }
}
