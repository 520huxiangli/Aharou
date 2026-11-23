package com.aharou.feature.plugin.workflow

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.plugin.domain.PluginManager
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 模板形态判定：只认 Operit 导出的「name / description / nodes / connections」结构。
 *
 * 插件包里可能有无数 JSON（schema、面板配置、状态文件），导入时不能照单全收；
 * 这里只做结构判定，真正的字段校验交给 [WorkflowTemplateImporter]。
 */
object WorkflowTemplateFormats {

    fun looksLikeTemplate(text: String): Boolean = runCatching {
        val element = Json.parseToJsonElement(text)
        if (element !is JsonObject) return@runCatching false
        val nodes = element["nodes"] as? JsonArray ?: return@runCatching false
        if (nodes.isEmpty()) return@runCatching false
        element["connections"] is JsonArray
    }.getOrDefault(false)

    fun templateName(text: String): String? = runCatching {
        val element = Json.parseToJsonElement(text)
        (element as? JsonObject)?.let { objectValue ->
            (objectValue["name"] as? JsonPrimitive)?.contentOrNull
        }
    }.getOrNull()
}

/**
 * 从已安装插件里同步工作流模板。
 *
 * 触发点：插件列表变化（安装/启停/卸载，见 `PluginToolManager.start`）与首次读取工作流列表
 * （[WorkflowHostApi] 的 getAll）。模板统一注册成**禁用**，是否启用由使用者决定。
 *
 * 扫描范围是已安装包里的资源目录，而不是 manifest 的 `workflow_templates` 声明：
 * Operit 的包经常把模板只放在 resources 目录下的 JSON 而不再登记，只认声明会漏掉。
 */
@Singleton
class WorkflowTemplateSync @Inject constructor(
    private val pluginManager: PluginManager,
    private val containerInstaller: ContainerInstaller,
    private val importer: WorkflowTemplateImporter,
) {

    /** 返回本轮新注册的模板数（已存在且内容未变的模板不计）。 */
    suspend fun syncInstalled(): Int = withContext(Dispatchers.IO) {
        // 首次读取工作流列表时插件管理器可能还没加载，先确保安装记录已入内存。
        runCatching { pluginManager.ensureLoaded() }
        val installedRoot = installedRootDir()
        if (!installedRoot.isDirectory) return@withContext 0
        var registered = 0
        pluginManager.plugins.value.forEach { plugin ->
            // 插件 id 直接拼成目录名，先卡住任意路径字符；异常 id 不该把扫目录带出插件根。
            if (!SAFE_PLUGIN_ID.matches(plugin.id)) return@forEach
            val pluginDir = File(installedRoot, plugin.id)
            if (!pluginDir.isDirectory) return@forEach
            collectTemplateFiles(pluginDir).forEach { file ->
                val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                if (!WorkflowTemplateFormats.looksLikeTemplate(text)) return@forEach
                val existedBefore = runCatching { importer.hasTemplate(plugin.id, WorkflowTemplateFormats.templateName(text)) }
                    .getOrDefault(false)
                importer.registerFromTemplate(text, plugin.id)
                    .onSuccess { if (!existedBefore) registered++ }
                    .onFailure { FileLogger.w(TAG, "同步工作流模板失败: ${plugin.id}/${file.name}", it) }
            }
        }
        if (registered > 0) FileLogger.i(TAG, "已登记 $registered 个工作流模板（默认未启用）")
        registered
    }

    /**
     * 插件安装根目录。目录布局与 `PluginManager` 保持一致（`<aharouDir>/plugins/installed/<id>`）；
     * 布局变了这里会扫不到模板，表现为「没登记」而不是报错，属于可接受的降级。
     */
    private fun installedRootDir(): File = File(containerInstaller.aharouDir, INSTALLED_RELATIVE)

    /**
     * 只收「资源目录下的 JSON」与「名字里带 workflow 的 JSON」：其余 JSON 是 schema、
     * 面板配置或状态文件，逐个 parse 只会白烧 IO。深度与大小都设上限，避免脏包拖垮同步。
     */
    private fun collectTemplateFiles(root: File): List<File> {
        val result = mutableListOf<File>()
        fun walk(dir: File, depth: Int, underResources: Boolean) {
            if (depth > MAX_DEPTH || result.size >= MAX_FILES) return
            val children = runCatching { dir.listFiles() }.getOrNull() ?: return
            children.forEach { child ->
                if (result.size >= MAX_FILES) return
                if (child.isDirectory) {
                    walk(child, depth + 1, underResources || child.name.equals("resources", ignoreCase = true))
                    return@forEach
                }
                if (!child.name.endsWith(".json", ignoreCase = true)) return@forEach
                if (!underResources && !child.name.contains("workflow", ignoreCase = true)) return@forEach
                if (child.length() > MAX_TEMPLATE_BYTES) return@forEach
                result += child
            }
        }
        walk(root, 0, underResources = false)
        return result
    }

    private companion object {
        const val TAG = "WorkflowTemplateSync"
        const val INSTALLED_RELATIVE = "plugins/installed"
        const val MAX_DEPTH = 6
        const val MAX_FILES = 40
        const val MAX_TEMPLATE_BYTES = 512 * 1024L
        val SAFE_PLUGIN_ID = Regex("[A-Za-z0-9._-]+")
    }
}
