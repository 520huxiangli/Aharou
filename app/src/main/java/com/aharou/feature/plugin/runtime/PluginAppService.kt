package com.aharou.feature.plugin.runtime

import android.content.Context
import com.aharou.feature.agent.domain.tool.device.DeviceNotificationListener
import com.aharou.feature.plugin.domain.PluginHostException
import com.aharou.feature.plugin.domain.PluginHostPaths
import com.aharou.feature.plugin.domain.PluginManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * `app.*` / `system.*` / `intent.*` / `notification.*`：启动与停止应用、读写系统设置、发 Intent、看通知。
 *
 * 前三组必须走系统 shell，因此统一经 [PluginShizukuRunner]：插件得在清单里声明 shizuku、
 * 使用者单独授权、Shizuku 处于就绪状态。任何一条不满足都返回明确错误码，不静默降级——
 * 插件拿到 `SHIZUKU_NOT_READY` 才能提示用户去开，而不是以为命令跑完了。
 *
 * 通知走系统「通知使用权」，与 AI 的 `device` 工具共用 [DeviceNotificationListener] 的缓存，
 * 未授权时同样明确报错。
 *
 * 所有拼进 shell 的参数都先按白名单校验再用单引号转义：包名、组件名、settings 键名与值都会原样
 * 交给 `am` / `settings`，不转义等于把注入口开在插件里。
 */
@Singleton
class PluginAppService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pluginManager: PluginManager,
    private val shizukuRunner: PluginShizukuRunner,
    private val systemTtsPlayer: com.aharou.feature.voice.data.SystemTtsPlayer
) {

    suspend fun startApp(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val packageName = requirePackageName(params, "package")
        val component = optionalString(params, "component")?.let(::requireComponent)
        // 给了组件就直接 am start；只给包名时用 monkey 解析 LAUNCHER，各 ROM 行为更一致。
        val command = if (component != null) {
            "am start -n ${quote(component)}"
        } else {
            "monkey -p ${quote(packageName)} -c android.intent.category.LAUNCHER 1"
        }
        val result = shizukuRunner.exec(pluginId, command, COMMAND_TIMEOUT_SECONDS)
        if (result.exitCode != 0) {
            throw PluginHostException(
                "APP_START_FAILED",
                "launch exit=${result.exitCode}: ${result.output.take(MAX_ERROR_OUTPUT_CHARS)}"
            )
        }
        return buildJsonObject {
            put("ok", true)
            put("package", packageName)
            put("output", result.output.take(MAX_OUTPUT_CHARS))
        }
    }

    suspend fun stopApp(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val packageName = requirePackageName(params, "package")
        // 停掉自己会把当前会话一起杀掉：这类自杀式调用一律拒绝，不做「聪明」的例外。
        if (packageName == context.packageName) {
            throw PluginHostException("PARAM_DENIED", "cannot force-stop the host app itself")
        }
        val result = shizukuRunner.exec(pluginId, "am force-stop ${quote(packageName)}", COMMAND_TIMEOUT_SECONDS)
        if (result.exitCode != 0) {
            throw PluginHostException(
                "APP_STOP_FAILED",
                "am force-stop exit=${result.exitCode}: ${result.output.take(MAX_ERROR_OUTPUT_CHARS)}"
            )
        }
        return buildJsonObject {
            put("ok", true)
            put("package", packageName)
        }
    }

    suspend fun listApps(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val includeSystem = (params["includeSystem"] as? JsonPrimitive)?.booleanOrNull ?: false
        val result = shizukuRunner.exec(
            pluginId,
            if (includeSystem) "pm list packages" else "pm list packages -3",
            COMMAND_TIMEOUT_SECONDS
        )
        if (result.exitCode != 0) {
            throw PluginHostException(
                "APP_LIST_FAILED",
                "pm list packages exit=${result.exitCode}: ${result.output.take(MAX_ERROR_OUTPUT_CHARS)}"
            )
        }
        val filter = optionalString(params, "filter")?.lowercase()
        val packages = result.output.lineSequence()
            .mapNotNull { line -> line.trim().removePrefix("package:").takeIf { it.isNotBlank() } }
            .filter { filter == null || it.lowercase().contains(filter) }
            .distinct()
            .sorted()
            .take(MAX_LISTED_PACKAGES)
            .toList()
        return buildJsonObject {
            put("packages", JsonArray(packages.map { JsonPrimitive(it) }))
            put("truncated", result.truncated)
        }
    }

    /** 通知缓存与 AI 的 `device` 工具同源；未开「通知使用权」时给出开启路径。 */
    fun listNotifications(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        if (!DeviceNotificationListener.isEnabled(context)) {
            throw PluginHostException(
                "NOTIFICATION_ACCESS_DENIED",
                "未开启通知使用权：请到「设置 → 权限与后台 → 通知使用权」勾选 Aharou 后重试。"
            )
        }
        val limit = ((params["limit"] as? JsonPrimitive)?.longOrNull ?: DEFAULT_NOTIFICATION_LIMIT)
            .coerceIn(1, MAX_NOTIFICATION_LIMIT).toInt()
        val onlyPackage = optionalString(params, "package")
        val entries = DeviceNotificationListener.snapshot(limit, onlyPackage)
        return buildJsonObject {
            put("notifications", JsonArray(entries.map { entry ->
                buildJsonObject {
                    put("key", entry.key)
                    put("package", entry.packageName)
                    put("title", entry.title)
                    put("text", entry.text)
                    put("postedAt", entry.postedAt)
                    put("ongoing", entry.ongoing)
                    put("canReply", entry.canReply)
                }
            }))
        }
    }

    suspend fun settingsGet(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val namespace = requireNamespace(params)
        val key = requireSettingsKey(params)
        val result = shizukuRunner.exec(
            pluginId,
            "settings get $namespace ${quote(key)}",
            COMMAND_TIMEOUT_SECONDS
        )
        if (result.exitCode != 0) {
            throw PluginHostException(
                "SETTINGS_GET_FAILED",
                "settings get exit=${result.exitCode}: ${result.output.take(MAX_ERROR_OUTPUT_CHARS)}"
            )
        }
        val value = result.output.trim().removePrefix("null")
        return buildJsonObject {
            put("namespace", namespace)
            put("key", key)
            put("value", value)
            put("unset", value.isEmpty())
        }
    }

    suspend fun settingsSet(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val namespace = requireNamespace(params)
        val key = requireSettingsKey(params)
        val value = optionalString(params, "value")
            ?: throw PluginHostException("PARAM_MISSING", "missing parameter: value")
        if (value.length > MAX_SETTINGS_VALUE_CHARS) {
            throw PluginHostException("TOO_LARGE", "settings value too long")
        }
        if (value.any { it.isISOControl() }) {
            throw PluginHostException("PARAM_INVALID", "control character in settings value")
        }
        val result = shizukuRunner.exec(
            pluginId,
            "settings put $namespace ${quote(key)} ${quote(value)}",
            COMMAND_TIMEOUT_SECONDS
        )
        if (result.exitCode != 0) {
            throw PluginHostException(
                "SETTINGS_SET_FAILED",
                "settings put exit=${result.exitCode}: ${result.output.take(MAX_ERROR_OUTPUT_CHARS)}"
            )
        }
        return buildJsonObject {
            put("ok", true)
            put("namespace", namespace)
            put("key", key)
        }
    }

    suspend fun startIntent(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val command = "am start ${intentArgs(params)}"
        val result = shizukuRunner.exec(pluginId, command, COMMAND_TIMEOUT_SECONDS)
        if (result.exitCode != 0) {
            throw PluginHostException(
                "INTENT_FAILED",
                "am start exit=${result.exitCode}: ${result.output.take(MAX_ERROR_OUTPUT_CHARS)}"
            )
        }
        return buildJsonObject {
            put("ok", true)
            put("output", result.output.take(MAX_OUTPUT_CHARS))
        }
    }

    suspend fun broadcastIntent(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val command = "am broadcast ${intentArgs(params)}"
        val result = shizukuRunner.exec(pluginId, command, COMMAND_TIMEOUT_SECONDS)
        if (result.exitCode != 0) {
            throw PluginHostException(
                "INTENT_FAILED",
                "am broadcast exit=${result.exitCode}: ${result.output.take(MAX_ERROR_OUTPUT_CHARS)}"
            )
        }
        return buildJsonObject {
            put("ok", true)
            put("output", result.output.take(MAX_OUTPUT_CHARS))
        }
    }

    /**
     * 组装 `am` 的参数串。只接受白名单形状的 action / component / 包名与字符串 extras：
     * 这些都是会进入 shell 的文本，靠转义不够，先把不该出现的字符挡在外面。
     */
    private fun intentArgs(params: JsonObject): String {
        val action = optionalString(params, "action")
        val data = optionalString(params, "data")
        val packageName = optionalString(params, "package")
        val component = optionalString(params, "component")
        if (action == null && data == null && packageName == null && component == null) {
            throw PluginHostException("PARAM_MISSING", "action, data, package or component required")
        }
        val builder = StringBuilder()
        action?.let { builder.append("-a ").append(quote(requireIntentAction(it))).append(' ') }
        data?.let { builder.append("-d ").append(quote(requireUri(it))).append(' ') }
        packageName?.let { builder.append("-p ").append(quote(requirePackageName(params, "package"))).append(' ') }
        component?.let { builder.append("-n ").append(quote(requireComponent(component))).append(' ') }
        val extras = params["extras"]
        if (extras is JsonObject) {
            if (extras.size > MAX_EXTRAS) {
                throw PluginHostException("PARAM_INVALID", "too many extras")
            }
            extras.forEach { (key, value) ->
                if (!EXTRA_KEY.matches(key)) throw PluginHostException("PARAM_INVALID", "invalid extra key: $key")
                val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw PluginHostException("PARAM_INVALID", "extra value must be a string: $key")
                if (text.length > MAX_SETTINGS_VALUE_CHARS) {
                    throw PluginHostException("TOO_LARGE", "extra value too long: $key")
                }
                builder.append("--es ").append(quote(key)).append(' ').append(quote(text)).append(' ')
            }
        }
        return builder.toString().trim()
    }

    private fun requirePackageName(params: JsonObject, key: String): String {
        val raw = PluginHostPaths.requireString(params, key).trim()
        if (!PACKAGE_NAME.matches(raw)) {
            throw PluginHostException("PARAM_INVALID", "invalid package name: $raw")
        }
        return raw
    }

    private fun requireComponent(raw: String): String {
        if (!COMPONENT.matches(raw.trim())) {
            throw PluginHostException("PARAM_INVALID", "invalid component: $raw")
        }
        return raw.trim()
    }

    private fun requireIntentAction(raw: String): String {
        if (!INTENT_ACTION.matches(raw.trim())) {
            throw PluginHostException("PARAM_INVALID", "invalid action: $raw")
        }
        return raw.trim()
    }

    private fun requireUri(raw: String): String {
        if (raw.length > MAX_URI_CHARS) throw PluginHostException("TOO_LARGE", "uri too long")
        if (raw.any { it.isISOControl() || it.isWhitespace() }) {
            throw PluginHostException("PARAM_INVALID", "invalid character in uri")
        }
        return raw
    }

    private fun requireNamespace(params: JsonObject): String {
        val raw = PluginHostPaths.requireString(params, "namespace").trim().lowercase()
        if (raw !in NAMESPACES) throw PluginHostException("PARAM_INVALID", "namespace must be system/secure/global")
        return raw
    }

    private fun requireSettingsKey(params: JsonObject): String {
        val raw = PluginHostPaths.requireString(params, "key").trim()
        if (!SETTINGS_KEY.matches(raw)) throw PluginHostException("PARAM_INVALID", "invalid settings key: $raw")
        return raw
    }

    private fun optionalString(params: JsonObject, key: String): String? {
        val raw = params[key] ?: return null
        return (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw PluginHostException("PARAM_INVALID", "$key must be a string")
    }

    /** 单引号包裹，内部单引号用 `'\''` 断开再接回；POSIX shell 通用。 */
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"


    /**
     * 各应用在前台的使用时长（毫秒）。
     *
     * 依赖「使用情况访问」特殊权限；没授权时抛错并说明去哪开，不返回空表冒充「没有数据」。
     */
    fun getAppUsageTime(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? android.app.usage.UsageStatsManager
            ?: throw PluginHostException("NOT_SUPPORTED", "UsageStatsManager unavailable")
        val days = params["days"]?.let { (it as? JsonPrimitive)?.longOrNull } ?: 1L
        val end = System.currentTimeMillis()
        val start = end - (days.coerceIn(1, 30) * 24L * 60L * 60L * 1000L)
        val stats = usage.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, start, end)
        if (stats.isNullOrEmpty()) {
            throw PluginHostException("PERMISSION_REQUIRED", "需要「使用情况访问」权限，去系统设置里给 Aharou 打开")
        }
        val totals = LinkedHashMap<String, Long>()
        stats.forEach { stat ->
            if (stat.totalTimeInForeground > 0) {
                totals[stat.packageName] = (totals[stat.packageName] ?: 0L) + stat.totalTimeInForeground
            }
        }
        return buildJsonObject {
            putJsonArray("apps") {
                totals.entries.sortedByDescending { it.value }.forEach { (pkg, ms) ->
                    addJsonObject {
                        put("package", pkg)
                        put("foregroundMs", ms)
                    }
                }
            }
            put("days", days)
        }
    }

    /**
     * 设备最后已知位置。
     *
     * 只取缓存位置，不主动发起定位请求：插件调用不该让 GPS 长时间亮着。
     */
    fun getLocation(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            ?: throw PluginHostException("NOT_SUPPORTED", "LocationManager unavailable")
        val providers = listOf(
            android.location.LocationManager.GPS_PROVIDER,
            android.location.LocationManager.NETWORK_PROVIDER,
            android.location.LocationManager.PASSIVE_PROVIDER
        )
        val best = providers.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
            ?: throw PluginHostException("LOCATION_UNAVAILABLE", "没有可用的最后位置，请先打开定位")
        return buildJsonObject {
            put("latitude", best.latitude)
            put("longitude", best.longitude)
            put("accuracy", best.accuracy.toDouble())
            put("provider", best.provider.orEmpty())
            put("time", best.time)
        }
    }

    /** 发广播。参数与 `am broadcast` 对齐，走 Shizuku 执行。 */
    suspend fun sendBroadcast(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val action = optionalString(params, "action") ?: throw pluginParamMissing("action")
        val builder = StringBuilder("am broadcast -a ").append(quote(requireIntentAction(action)))
        val extras = params["extras"]
        if (extras is JsonObject) {
            extras.entries.take(MAX_EXTRAS).forEach { (key, value) ->
                val text = (value as? JsonPrimitive)?.contentOrNull ?: return@forEach
                builder.append(" --es ").append(quote(key)).append(' ').append(quote(text))
            }
        }
        val component = optionalString(params, "component")
        component?.let { builder.append(" -n ").append(quote(requireComponent(it))) }
        val result = shizukuRunner.exec(pluginId, builder.toString(), COMMAND_TIMEOUT_SECONDS)
        if (result.exitCode != 0) {
            throw PluginHostException("BROADCAST_FAILED", "am broadcast exit=${result.exitCode}: ${result.output.take(MAX_ERROR_OUTPUT_CHARS)}")
        }
        return buildJsonObject {
            put("ok", true)
            put("output", result.output.take(MAX_OUTPUT_CHARS))
        }
    }

    private fun pluginParamMissing(key: String) =
        PluginHostException("PARAM_MISSING", "$key is required")


    /** 卸载应用。走 `pm uninstall`：系统会弹确认框，插件不能静默卸载。 */
    suspend fun uninstallApp(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val packageName = requirePackageName(params, "package")
        val result = shizukuRunner.exec(pluginId, "pm uninstall ${quote(packageName)}", COMMAND_TIMEOUT_SECONDS)
        val output = result.output.take(MAX_OUTPUT_CHARS)
        val succeeded = result.exitCode == 0 && !output.contains("Failure")
        if (!succeeded) {
            throw PluginHostException("UNINSTALL_FAILED", "pm uninstall exit=${result.exitCode}: ${output.take(MAX_ERROR_OUTPUT_CHARS)}")
        }
        return buildJsonObject {
            put("ok", true)
            put("package", packageName)
            put("output", output)
        }
    }

    /**
     * 安装 APK。
     *
     * 只接受宿主文件系统里真实存在的路径：Plugins 给的容器路径在 Shizuku 侧不可见，
     * 直接交给 pm 会失败，所以先把路径换算到宿主侧，换算不出来就明确报错。
     */
    suspend fun installApp(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val rawPath = optionalString(params, "path") ?: throw pluginParamMissing("path")
        val apk = resolveHostFile(rawPath)
            ?: throw PluginHostException("PATH_DENIED", "无法把容器路径映射到宿主路径: $rawPath")
        if (!apk.isFile) throw PluginHostException("IO_ERROR", "APK 不存在: $rawPath")
        val result = shizukuRunner.exec(pluginId, "pm install -r ${quote(apk.absolutePath)}", COMMAND_TIMEOUT_SECONDS)
        val output = result.output.take(MAX_OUTPUT_CHARS)
        if (result.exitCode != 0 || output.contains("Failure")) {
            throw PluginHostException("INSTALL_FAILED", "pm install exit=${result.exitCode}: ${output.take(MAX_ERROR_OUTPUT_CHARS)}")
        }
        return buildJsonObject {
            put("ok", true)
            put("path", rawPath)
            put("output", output)
        }
    }

    /**
     * 容器路径 → 宿主文件。
     *
     * 容器里看到的 `/root/...` 在宿主上是几个不同目录的绑定，Shizuku 看不到容器路径，
     * 所以安装/打开文件前必须先换算；映射不上就返回 null，由调用方明确报错。
     */
    private fun resolveHostFile(containerPath: String): java.io.File? {
        val path = containerPath.trim()
        val filesRoot = context.filesDir
        return when {
            path.startsWith("/root/.aharou/") ->
                java.io.File(filesRoot, "aharou/" + path.removePrefix("/root/.aharou/"))
            path.startsWith("/root/shared/") ->
                java.io.File(filesRoot, "aharou-global/shared/" + path.removePrefix("/root/shared/"))
            path.startsWith("/root/workspace/") -> {
                val rest = path.removePrefix("/root/workspace/")
                val project = java.io.File(filesRoot, "projects").listFiles()
                    ?.firstOrNull { it.isDirectory }
                project?.let { java.io.File(it, rest) }
            }
            else -> null
        }
    }


    /**
     * 用系统应用打开文件。
     *
     * 容器路径先换算到宿主文件，再经 FileProvider 授权成 content URI：
     * 直接给 file:// 会被系统拦下，等于打开失败。
     */
    fun openFile(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val rawPath = optionalString(params, "path") ?: throw pluginParamMissing("path")
        val file = resolveHostFile(rawPath)
            ?: throw PluginHostException("PATH_DENIED", "无法把容器路径映射到宿主路径: $rawPath")
        if (!file.isFile) throw PluginHostException("IO_ERROR", "文件不存在: $rawPath")
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        val mime = optionalString(params, "mimeType") ?: guessMimeType(file.name)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }.onFailure { error ->
            throw PluginHostException("OPEN_FAILED", "没有能打开该文件的应用: ${error.message}")
        }
        return buildJsonObject {
            put("ok", true)
            put("path", rawPath)
            put("mimeType", mime)
        }
    }

    /** 按扩展名猜 MIME；猜不出就交给系统按通用二进制处理。 */
    private fun guessMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt", "md", "log" -> "text/plain"
            "json" -> "application/json"
            "html", "htm" -> "text/html"
            "pdf" -> "application/pdf"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "mp4" -> "video/mp4"
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            else -> "application/octet-stream"
        }
    }


    /** 枚举系统窗口（含影子屏所在的 display）。依赖无障碍服务，未开启时明确报错。 */
    fun listWindows(pluginId: String): JsonElement {
        ensureActive(pluginId)
        val service = com.aharou.accessibility.AharouAccessibilityService.getInstance()
            ?: throw PluginHostException("PERMISSION_REQUIRED", "需要开启无障碍服务才能枚举窗口")
        return buildJsonObject {
            putJsonArray("windows") {
                service.windowInfos().forEach { info ->
                    addJsonObject {
                        put("windowId", (info["windowId"] as? Number)?.toInt() ?: 0)
                        put("displayId", (info["displayId"] as? Number)?.toInt() ?: 0)
                        put("type", info["type"]?.toString().orEmpty())
                        put("title", info["title"]?.toString().orEmpty())
                        put("focused", info["focused"] == true)
                        put("active", info["active"] == true)
                        put("rootPackage", info["rootPackage"]?.toString().orEmpty())
                    }
                }
            }
        }
    }

    /**
     * 把指定窗口带到前台。
     *
     * 无障碍 API 不能直接「聚焦某个窗口」，只能把它的应用唤起来；
     * 没给 windowId 时按「当前前台应用」处理。
     */
    suspend fun focusWindow(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val service = com.aharou.accessibility.AharouAccessibilityService.getInstance()
            ?: throw PluginHostException("PERMISSION_REQUIRED", "需要开启无障碍服务才能聚焦窗口")
        val windowId = params["windowId"]?.let { (it as? JsonPrimitive)?.longOrNull }
        val target = if (windowId == null) {
            service.foregroundPackage().first
        } else {
            service.windowInfos()
                .firstOrNull { (it["windowId"] as? Number)?.toLong() == windowId }
                ?.get("rootPackage")?.toString()
        }
        if (target.isNullOrBlank()) {
            throw PluginHostException("WINDOW_NOT_FOUND", "找不到窗口或它的应用包名")
        }
        val result = shizukuRunner.exec(
            pluginId,
            "monkey -p ${quote(target)} -c android.intent.category.LAUNCHER 1",
            COMMAND_TIMEOUT_SECONDS
        )
        return buildJsonObject {
            put("ok", result.exitCode == 0)
            put("package", target)
            put("output", result.output.take(MAX_OUTPUT_CHARS))
        }
    }


    /**
     * 用内置系统 TTS 念一段文字。
     *
     * 不依赖任何外部转发软件：引擎不可用时明确返回失败原因，插件自己决定要不要降级。
     */
    suspend fun speak(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val text = optionalString(params, "text") ?: throw pluginParamMissing("text")
        if (text.isBlank()) throw PluginHostException("PARAM_INVALID", "text 不能为空")
        val ok = systemTtsPlayer.speakAndWait(text)
        if (!ok) {
            throw PluginHostException("TTS_UNAVAILABLE", "系统没有可用的语音引擎，或播放被拒绝")
        }
        return buildJsonObject {
            put("ok", true)
            put("text", text.take(120))
        }
    }


    /** 把插件导出的文件登记进系统媒体库；登记是异步的，返回可定位的 URI（拿不到就给路径）。 */
    fun registerMedia(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val rawPath = optionalString(params, "path") ?: throw pluginParamMissing("path")
        val file = resolveHostFile(rawPath)
            ?: throw PluginHostException("PATH_DENIED", "无法把容器路径映射到宿主路径: $rawPath")
        if (!file.isFile) throw PluginHostException("IO_ERROR", "文件不存在: $rawPath")
        val listener = java.util.concurrent.CountDownLatch(1)
        val holder = arrayOfNulls<android.net.Uri>(1)
        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            null
        ) { _, uri ->
            holder[0] = uri
            listener.countDown()
        }
        listener.await(5, java.util.concurrent.TimeUnit.SECONDS)
        val uri = holder[0]
        return buildJsonObject {
            put("ok", true)
            put("path", rawPath)
            put("id", uri?.toString() ?: file.absolutePath)
        }
    }


    /** 读写 WebView 的 Cookie 罐。插件常用来复用登录态。 */
    fun cookies(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val url = optionalString(params, "url") ?: throw pluginParamMissing("url")
        val manager = android.webkit.CookieManager.getInstance()
        val action = optionalString(params, "action") ?: "get"
        when (action) {
            "get" -> {
                val cookie = manager.getCookie(url).orEmpty()
                return buildJsonObject {
                    put("ok", true)
                    put("cookie", cookie)
                }
            }
            "set" -> {
                val cookie = optionalString(params, "cookie") ?: throw pluginParamMissing("cookie")
                manager.setCookie(url, cookie)
                return buildJsonObject {
                    put("ok", true)
                }
            }
            else -> throw PluginHostException("PARAM_INVALID", "action 只支持 get / set")
        }
    }


    /**
     * 读另一个插件包内的资源文件。
     *
     * 只能读已安装插件的 `operit/pkg/` 目录，且必须先在该包清单里声明过这个资源：
     * 否则等于给了插件遍历别人包内容的能力。
     */
    fun readResourceFromPackage(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val target = optionalString(params, "package") ?: throw pluginParamMissing("package")
        val key = optionalString(params, "key") ?: throw pluginParamMissing("key")
        pluginManager.ensureLoaded()
        val other = pluginManager.plugins.value.firstOrNull { it.id == target }
            ?: throw PluginHostException("PLUGIN_NOT_FOUND", "目标插件未安装: $target")
        val root = java.io.File(pluginManager.installDirFor(target), "operit/pkg")
        val manifestFile = java.io.File(root, "manifest.json")
        if (!manifestFile.isFile) throw PluginHostException("IO_ERROR", "目标插件缺少清单: $target")
        val manifest = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(manifestFile.readText()) as JsonObject
        }.getOrNull() ?: throw PluginHostException("IO_ERROR", "目标插件清单无法解析: $target")
        val resources = manifest["resources"] as? kotlinx.serialization.json.JsonArray
        val entry = resources?.mapNotNull { it as? JsonObject }?.firstOrNull {
            (it["key"] as? JsonPrimitive)?.contentOrNull == key
        } ?: throw PluginHostException("RESOURCE_MISSING", "目标插件没有声明资源: $key")
        val path = (entry["path"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val file = java.io.File(root, path).canonicalFile
        if (!file.path.startsWith(root.canonicalFile.path)) {
            throw PluginHostException("PATH_DENIED", "资源路径越界: $path")
        }
        if (!file.isFile) throw PluginHostException("RESOURCE_MISSING", "资源文件不存在: $path")
        return buildJsonObject {
            put("ok", true)
            put("base64", android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP))
            put("path", path)
            put("package", target)
        }
    }

    private fun ensureActive(pluginId: String) {
        pluginManager.ensureLoaded()
        pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
    }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 30L
        const val MAX_OUTPUT_CHARS = 4_000
        const val MAX_ERROR_OUTPUT_CHARS = 2_000
        const val MAX_LISTED_PACKAGES = 2_000
        const val DEFAULT_NOTIFICATION_LIMIT = 20L
        const val MAX_NOTIFICATION_LIMIT = 50L
        const val MAX_SETTINGS_VALUE_CHARS = 4_096
        const val MAX_URI_CHARS = 4_096
        const val MAX_EXTRAS = 20

        val NAMESPACES = setOf("system", "secure", "global")
        val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        val COMPONENT = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*/[A-Za-z0-9_.$]+$")
        val INTENT_ACTION = Regex("^[A-Za-z][A-Za-z0-9_.]*$")
        val SETTINGS_KEY = Regex("^[A-Za-z0-9_.:-]{1,128}$")
        val EXTRA_KEY = Regex("^[A-Za-z0-9_.-]{1,64}$")
    }
}
