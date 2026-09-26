package com.aicode.feature.browser

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * 浏览器自动化工具（browser）——Aharou 移植版。
 *
 * 引擎 = 自 OpenMinis 移植的 [BrowserTabPool] / [BrowserUseManager]
 * （"browser_use" 同语义）：最多 3 个标签页，动作集见 [BrowserAction]；
 * 截图以 JPEG 随结果返回（走图像通道）。
 */
class BrowserTool @Inject constructor(
    private val tabPool: BrowserTabPool
) : AgentTool() {

    private companion object {
        const val TAG = "BrowserTool"
    }

    override val name = "browser"

    override val description =
        "控制内置浏览器（最多 3 个标签页，与 Minis 的 browser_use 同引擎、同语义）。" +
            "action 取值：navigate/screenshot/click/type/get_text/scroll/get_page_info/execute_js/" +
            "find_elements/hover/get_readable/set_user_agent/set_viewport/get_backbone/fetch/" +
            "new_tab/close_tab/list_tabs/get_cookies/set_cookies/scroll_and_collect/wait_for_dom_stable。" +
            "交互类动作用 selector；需要看到页面视觉内容时显式调用 action=screenshot（会作为图片返回）；" +
            "多标签操作可带 tab_id（缺省作用于当前激活标签）。"

    override val capabilities = setOf(ToolCapability.NETWORK_READ, ToolCapability.NETWORK_WRITE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter("action", ParameterType.STRING, "浏览器动作", true,
            enum = BrowserAction.allValues),
        "url" to ToolParameter("url", ParameterType.STRING, "navigate/fetch/new_tab 的目标 URL", false),
        "selector" to ToolParameter("selector", ParameterType.STRING,
            "click/type/hover/find_elements/get_text/scroll 的元素选择器（CSS）", false),
        "text" to ToolParameter("text", ParameterType.STRING, "type：要输入的文本", false),
        "coordinate_x" to ToolParameter("coordinate_x", ParameterType.INTEGER, "click：按坐标点击的 X", false),
        "coordinate_y" to ToolParameter("coordinate_y", ParameterType.INTEGER, "click：按坐标点击的 Y", false),
        "direction" to ToolParameter("direction", ParameterType.STRING, "scroll 方向", false,
            enum = listOf("up", "down")),
        "amount" to ToolParameter("amount", ParameterType.INTEGER, "scroll 像素量（默认 500）", false),
        "script" to ToolParameter("script", ParameterType.STRING,
            "execute_js：JS 代码（支持 await / 顶层 return）", false),
        "user_agent" to ToolParameter("user_agent", ParameterType.STRING, "set_user_agent：UA 档位", false,
            enum = listOf("desktop_chrome", "mobile_chrome")),
        "max_depth" to ToolParameter("max_depth", ParameterType.INTEGER, "get_backbone：DOM 树深度（默认 5）", false),
        "tab_id" to ToolParameter("tab_id", ParameterType.INTEGER, "目标标签页 ID", false),
        "full_page" to ToolParameter("full_page", ParameterType.BOOLEAN, "screenshot：整页截图（默认 false）", false),
        "viewport_width" to ToolParameter("viewport_width", ParameterType.INTEGER, "set_viewport：宽（CSS px）", false),
        "viewport_height" to ToolParameter("viewport_height", ParameterType.INTEGER, "set_viewport：高（CSS px）", false),
        "reset" to ToolParameter("reset", ParameterType.BOOLEAN, "set_viewport：清除会话级覆盖", false),
        "keywords" to ToolParameter("keywords", ParameterType.ARRAY, "get_cookies/scroll_and_collect：关键词过滤", false,
            itemsSchema = mapOf("type" to "string")),
        "fuzzy" to ToolParameter("fuzzy", ParameterType.BOOLEAN, "get_cookies：模糊匹配（默认 false）", false),
        "item_selector" to ToolParameter("item_selector", ParameterType.STRING, "scroll_and_collect：条目选择器", false),
        "scroll_count" to ToolParameter("scroll_count", ParameterType.INTEGER, "scroll_and_collect：滚动次数（默认 10）", false),
        "timeout" to ToolParameter("timeout", ParameterType.INTEGER, "wait_for_dom_stable：超时毫秒", false),
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val json = JsonObject(args).toString()
        val input = BrowserActionInput.parse(json)
            ?: return ToolResult.Error("无效的 browser 参数（检查 action 与必填项）", "INVALID_INPUT")
        return try {
            val result = tabPool.execute(input)
            val data = buildJsonObject {
                put("result", result.text)
                result.pageURL?.let { put("pageURL", it) }
                result.tabId?.let { put("tabId", it) }
                result.imageFilePath?.let { put("imagePath", it) }
                result.fetchedFileName?.let { put("fetchedFileName", it) }
            }
            if (result.success) {
                val images = result.base64Image?.let {
                    listOf(AgentImage(mimeType = "image/jpeg", base64Data = it))
                } ?: emptyList()
                ToolResult.Success(data, images)
            } else {
                ToolResult.Error(result.text, "BROWSER_ACTION_FAILED")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "browser action failed: ${e.message}", e)
            ToolResult.Error("浏览器操作失败: ${e.message}")
        }
    }
}
