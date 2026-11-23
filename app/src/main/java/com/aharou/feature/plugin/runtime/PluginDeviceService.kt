package com.aharou.feature.plugin.runtime

import android.content.Context
import android.graphics.Rect
import com.aharou.accessibility.AharouAccessibilityService
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.plugin.domain.PluginHostException
import com.aharou.feature.plugin.domain.PluginManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * `device.*` 原语：屏幕点按、滑动、按键、写文字、截图与页面信息。
 *
 * 无障碍逻辑全部交给内置 `a11y` 工具执行——直接取 [ToolRegistry] 里的实例调 `execute`，
 * 不经过 `tools.execute`：那条路会经过权限策略与审批队列，而插件调用应当按宿主方法的语义
 * 同步返回，不被工具审批挡住。由此 a11y 自身的行为（前后画面回执、第三方前台时拒写并提示
 * 改用影子屏）也原样保留，插件看到的结果与 AI 用同一个工具时一致。
 *
 * 长按没有独立的无障碍动作：按住不动 600ms 在系统那里就是一次长按，因此用同一工具、
 * 起点终点相同的 swipe 表达，避免另写一套手势与判据。
 */
@Singleton
class PluginDeviceService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pluginManager: PluginManager,
    private val toolRegistry: ToolRegistry
) {

    suspend fun tap(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val args = mutableMapOf<String, JsonElement>("action" to JsonPrimitive("tap"))
        copyString(params, "id", args)
        copyInt(params, "x", args)
        copyInt(params, "y", args)
        copyString(params, "label", args)
        return flatten(runTool(args))
    }

    /** 长按 = 起终点相同、时长更长的滑动；`id` 给的是元素时取元素中心。 */
    suspend fun longPress(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val duration = (params["duration"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_LONG_PRESS_MS
        val id = (params["id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val point = if (id.isNotEmpty()) nodeCenter(id) else {
            val x = (params["x"] as? JsonPrimitive)?.intOrNull
            val y = (params["y"] as? JsonPrimitive)?.intOrNull
            if (x == null || y == null) {
                throw PluginHostException("PARAM_MISSING", "longPress needs id or x/y")
            }
            x to y
        }
        val args = mutableMapOf<String, JsonElement>(
            "action" to JsonPrimitive("swipe"),
            "x" to JsonPrimitive(point.first),
            "y" to JsonPrimitive(point.second),
            "x2" to JsonPrimitive(point.first),
            "y2" to JsonPrimitive(point.second),
            "duration" to JsonPrimitive(duration.coerceIn(MIN_LONG_PRESS_MS, MAX_LONG_PRESS_MS))
        )
        copyString(params, "label", args)
        return flatten(runTool(args))
    }

    suspend fun swipe(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val args = mutableMapOf<String, JsonElement>("action" to JsonPrimitive("swipe"))
        copyInt(params, "x", args)
        copyInt(params, "y", args)
        copyInt(params, "x2", args)
        copyInt(params, "y2", args)
        copyInt(params, "duration", args)
        copyString(params, "label", args)
        return flatten(runTool(args))
    }

    suspend fun key(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val args = mutableMapOf<String, JsonElement>("action" to JsonPrimitive("key"))
        copyString(params, "key", args)
        return flatten(runTool(args))
    }

    suspend fun setText(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val args = mutableMapOf<String, JsonElement>("action" to JsonPrimitive("setText"))
        copyString(params, "id", args)
        copyString(params, "text", args)
        copyString(params, "label", args)
        return flatten(runTool(args))
    }

    /** 页面信息：前台应用与 Activity、屏幕像素、窗口数量、键盘是否在。读不到无障碍时明确报错。 */
    fun pageInfo(pluginId: String): JsonElement {
        ensureActive(pluginId)
        val service = requireService()
        val (foregroundPackage, activity) = service.foregroundPackage()
        val screen = service.screenSize()
        val windows = service.windowInfos()
        return buildJsonObject {
            put("package", foregroundPackage ?: "")
            put("activity", activity ?: "")
            screen?.let {
                put("width", it.first)
                put("height", it.second)
            }
            put("windowCount", windows.size)
            put("keyboardShown", windows.any { it["type"] == "input_method" })
            put("appName", appLabel(foregroundPackage) ?: "")
        }
    }

    /** 截图以 Base64 直接回给插件；宿主方法没有多模态通道，图片只能走字符串。 */
    suspend fun screenshot(pluginId: String): JsonElement {
        ensureActive(pluginId)
        val result = runTool(mapOf("action" to JsonPrimitive("shot")))
        if (result is ToolResult.Error) throw toHostError(result)
        val success = result as? ToolResult.Success ?: throw ToolHostFailure()
        val image = success.images.firstOrNull()
            ?: throw PluginHostException("SHOT_FAILED", "no screenshot data")
        val data = success.data as? JsonObject
        return buildJsonObject {
            put("ok", true)
            put("mimeType", image.mimeType)
            put("base64", image.base64Data)
            (data?.get("caption") as? JsonPrimitive)?.contentOrNull?.let { put("caption", it) }
        }
    }

    private suspend fun runTool(args: Map<String, JsonElement>): ToolResult {
        val tool = toolRegistry.getTool(A11Y_TOOL)
            ?: throw PluginHostException("SERVICE_UNAVAILABLE", "a11y tool is not registered")
        return try {
            tool.execute(args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PluginHostException) {
            throw e
        } catch (e: ToolHostFailure) {
            throw e
        } catch (e: Exception) {
            throw PluginHostException("DEVICE_FAILED", e.message ?: e.javaClass.simpleName)
        }
    }

    /** 工具结果摊平成宿主方法形状：`{ok:true, ...data}`，错误转成稳定的宿主错误码。 */
    private fun flatten(result: ToolResult): JsonElement {
        when (result) {
            is ToolResult.Error -> throw toHostError(result)
            is ToolResult.Partial -> {
                val data = result.data as? JsonObject
                return buildJsonObject {
                    put("ok", false)
                    data?.forEach { (key, value) -> put(key, value) }
                    put("message", result.message)
                }
            }
            is ToolResult.Success -> {
                val data = result.data as? JsonObject
                return buildJsonObject {
                    put("ok", true)
                    if (data != null) {
                        data.forEach { (key, value) -> put(key, value) }
                    } else {
                        put("result", result.data)
                    }
                }
            }
        }
    }

    private fun toHostError(result: ToolResult.Error): PluginHostException =
        PluginHostException(
            result.code.ifBlank { "DEVICE_FAILED" },
            result.message
        )

    private fun nodeCenter(id: String): Pair<Int, Int> {
        val service = requireService()
        val node = service.nodeRegistry.get(id)
            ?: throw PluginHostException("STALE_NODE", "节点 id「$id」已过期——请重新 dump/find 再操作。")
        val rect = Rect().also { node.getBoundsInScreen(it) }
        if (rect.width() <= 0 || rect.height() <= 0) {
            throw PluginHostException("TAP_FAILED", "节点没有可点面积: $id")
        }
        return rect.exactCenterX().toInt() to rect.exactCenterY().toInt()
    }

    private fun requireService(): AharouAccessibilityService =
        AharouAccessibilityService.getInstance()
            ?: throw PluginHostException(
                "A11Y_NOT_ENABLED",
                "无障碍服务未开启：请到「设置 → 权限与后台 → 无障碍」开启 Aharou 无障碍服务后重试。"
            )

    private fun appLabel(packageName: String?): String? {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(info).toString()
        }.getOrNull()
    }

    private fun ensureActive(pluginId: String) {
        pluginManager.ensureLoaded()
        pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
    }

    private fun copyString(params: JsonObject, key: String, target: MutableMap<String, JsonElement>) {
        val value = params[key] ?: return
        if (value !is JsonPrimitive || !value.isString) throw paramInvalid("$key must be a string")
        target[key] = value
    }

    private fun copyInt(params: JsonObject, key: String, target: MutableMap<String, JsonElement>) {
        val value = params[key] ?: return
        val number = (value as? JsonPrimitive)?.intOrNull ?: throw paramInvalid("$key must be a number")
        target[key] = JsonPrimitive(number)
    }

    private fun paramInvalid(message: String): PluginHostException =
        PluginHostException("PARAM_INVALID", message)

    /** 工具返回了既非 Success 也非 Error 的结果（当前实现里不会发生）。 */
    private class ToolHostFailure : Exception("unexpected tool result shape")

    private companion object {
        const val A11Y_TOOL = "a11y"
        const val DEFAULT_LONG_PRESS_MS = 600
        const val MIN_LONG_PRESS_MS = 300
        const val MAX_LONG_PRESS_MS = 5_000
    }
}
