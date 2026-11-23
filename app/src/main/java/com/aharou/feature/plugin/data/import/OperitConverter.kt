package com.aharou.feature.plugin.data.import

import com.aharou.feature.plugin.domain.PluginError
import com.aharou.feature.plugin.domain.PluginErrorCode
import com.aharou.feature.plugin.domain.PluginUiSlots
import com.aharou.feature.plugin.domain.SemVer
import com.aharou.feature.plugin.workflow.WorkflowTemplateFormats
import com.aharou.core.util.FileLogger
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 导入页展示、也写进 `.operit-origin.json` 的摘要。 */
data class OperitImportReport(
    val format: OperitPackageFormat,
    val pluginId: String,
    val displayName: String,
    val toolCount: Int,
    val adviceCount: Int,
    val compat: OperitCompatReport,
    /** 包里识别出的工作流模板数；导入后默认未启用，需要在工作流里手动开启。 */
    val workflowTemplateCount: Int = 0,
    val workflowTemplateNames: List<String> = emptyList()
)

sealed interface OperitConversion {
    data class Ready(val zip: ByteArray, val report: OperitImportReport) : OperitConversion

    data class Failed(val errors: List<PluginError>) : OperitConversion
}

/**
 * 把识别出的 Operit 包转换成宿主自己的 `plugin.json` 插件包。
 *
 * 元数据与工具函数约定参考 AAswordman/Operit（LGPL-3.0），改动：
 * 输出 Aharou 插件清单；工具函数经生成的 `operit/main.js` 适配成宿主的 JSON-RPC handler；
 * 原包内容原样放在 `operit/pkg/` 下，不做改写，便于对照排查。
 *
 * `advice: true` 的工具在 Operit 里只是注入给模型的提示词，这里改成技能；
 * 兼容性结论只写进来源文件与导入页，不影响能否安装。
 */
class OperitConverter {

    fun convert(source: OperitSource): OperitConversion {
        if (source.format == OperitPackageFormat.SKILL) return convertSkill(source)

        val metadata = source.scripts.mapNotNull { it.metadata }
        val primary = metadata.firstOrNull()
        val manifest = source.manifest

        val nameZh = primary?.displayNameZh ?: manifest?.displayNameZh
        val nameEn = primary?.displayNameEn ?: manifest?.displayNameEn
        val displayName = cleanText(
            primary?.displayName ?: manifest?.displayName ?: primary?.name ?: source.sourceName
                ?: "Operit Plugin",
            80
        ).ifEmpty { "Operit Plugin" }
        // 面板入口只是附加信息：解析失败就当作没有，绝不能因为找不到 screen 让插件装不上
        val uiEntries = runCatching {
            OperitUiEntryParser.parse(source.scripts.associate { it.path to it.text }, displayName)
        }.getOrDefault(emptyList()).mapNotNull { entry ->
            // 原包文件全部落在 operit/pkg/ 下，清单里的 screen 要跟着换前缀
            val screen = "operit/pkg/${entry.screen}"
            if (isSafeOutputPath(screen)) ConvertedUiEntry(entry.id, entry.title, screen) else null
        }
        val pluginId = "operit.${slug(source.packageId ?: primary?.name ?: source.sourceName ?: displayName)}"
        val version = normalizeVersion(primary?.version ?: manifest?.version)
        val descZh = cleanText(primary?.descriptionZh ?: manifest?.descriptionZh, 600)
        val descEn = cleanText(primary?.descriptionEn ?: manifest?.descriptionEn, 600)
        val descDefault = cleanText(
            primary?.descriptionDefault ?: manifest?.description ?: descZh.ifEmpty { descEn },
            600
        ).ifEmpty { "Imported from an Operit tool package." }
        val author = primary?.author ?: manifest?.author

        val tools = mutableListOf<ConvertedTool>()
        val advice = mutableListOf<AdviceItem>()
        val usedIds = HashSet<String>()
        source.scripts.forEach { script ->
            val scriptMetadata = script.metadata ?: return@forEach
            scriptMetadata.tools.forEach { tool ->
                if (tool.advice) {
                    advice += AdviceItem(
                        name = cleanText(tool.displayName ?: tool.name, 80).ifEmpty { tool.name },
                        description = cleanText(tool.description, 2000)
                    )
                    return@forEach
                }
                val id = uniqueId(sanitizeLocalId(tool.name), usedIds)
                tools += ConvertedTool(
                    id = id,
                    originalName = tool.name,
                    file = script.path,
                    title = cleanText(tool.displayName ?: tool.name, 80).ifEmpty { id },
                    description = cleanText(tool.description, 600).ifEmpty { tool.name },
                    parameters = tool.parameters
                )
            }
        }
        // 少数包把工具写在 ToolPkg.registerTool({...}) 里，只在运行时注册；静态再扫一遍补齐。
        // 与 METADATA 同名的声明以 METADATA 为准（字段更全），所以先登记已占用的 file#name。
        val declared = tools.map { "${it.file}#${it.originalName}" }.toHashSet()
        val runtimeTools = OperitRuntimeToolScanner.scan(source.scripts).toMutableList()
        // registerApi 只有方法名、没有参数与说明（且很多是给 UI 用的），只当包里本来就找不到工具时兜底。
        if (tools.isEmpty() && runtimeTools.isEmpty()) {
            runtimeTools += OperitRuntimeToolScanner.scanApiFallback(source.scripts)
        }
        // 宿主清单的 contributions 有数量上限（与 PluginManifestParser.MAX_IDS_PER_TYPE 一致），
        // 超了整份清单会校验失败、包就装不上；宁可少列几个也不能让导入变成失败。
        val capacity = (MAX_TOOLS_PER_PLUGIN - tools.size).coerceAtLeast(0)
        if (runtimeTools.size > capacity) {
            FileLogger.i(TAG, "运行时注册的工具超过清单上限，截断 ${runtimeTools.size - capacity} 个")
            runtimeTools.subList(capacity, runtimeTools.size).clear()
        }
        runtimeTools.forEach { runtime ->
            if (!declared.add("${runtime.file}#${runtime.name}")) return@forEach
            val id = uniqueId(sanitizeLocalId(runtime.name), usedIds)
            tools += ConvertedTool(
                id = id,
                originalName = runtime.name,
                file = runtime.file,
                title = cleanText(runtime.displayName ?: runtime.name, 80).ifEmpty { id },
                description = cleanText(runtime.description, 600).let { text ->
                    // registerApi 只有方法名；宿主清单不接受空描述，用包自身的描述，不按方法名编造
                    if (text.isEmpty() && runtime.openSchema) descDefault else text.ifEmpty { runtime.name }
                },
                parameters = runtime.parameters,
                runtimeRegistered = true,
                openSchema = runtime.openSchema
            )
        }
        // 没有工具不当作失败：纯面板包与只注册 hook 的包也要能装，下面会补一份说明技能。
        val compat = OperitCompatScanner.scan(source.scripts)
        val workflowTemplates = collectWorkflowTemplates(source.files)
        if (workflowTemplates.isNotEmpty()) {
            FileLogger.i(
                TAG,
                "导入包发现 ${workflowTemplates.size} 个工作流模板（导入后默认未启用）: " +
                    workflowTemplates.joinToString { it.second ?: it.first }
            )
        }
        val adviceFile = if (advice.isEmpty()) null else "$ADVICE_SKILL_DIR/SKILL.md"
        // 宿主清单不允许空 contributes：纯面板包、只注册 hook 的包没有可表达的能力时，
        // 补一份说明技能让包仍能装上，问题留在说明与 .operit-origin.json 里。
        val compatNote = if (tools.isEmpty() && advice.isEmpty() && uiEntries.isEmpty()) {
            buildCompatNote(displayName, descDefault, compat)
        } else {
            null
        }
        val skills = listOfNotNull(
            adviceFile?.let { ConvertedSkill(ADVICE_SKILL_ID, it) },
            compatNote?.let { ConvertedSkill(COMPAT_SKILL_ID, "$COMPAT_SKILL_DIR/SKILL.md") }
        )
        val files = mutableListOf<OutFile>()
        files += OutFile.text(
            "plugin.json",
            buildManifestText(
                pluginId = pluginId,
                displayName = displayName,
                nameZh = nameZh,
                nameEn = nameEn,
                description = descDefault,
                descriptionZh = descZh,
                descriptionEn = descEn,
                version = version,
                tools = tools,
                skills = skills,
                uiEntries = uiEntries
            )
        )
        if (tools.isNotEmpty() || uiEntries.isNotEmpty()) {
            files += OutFile.text(ADAPTER_PATH, buildAdapterScript(tools, source.mainFile))
        }
        tools.forEach { tool -> files += OutFile.text(schemaPath(tool.id), buildSchemaText(tool)) }
        adviceFile?.let { files += OutFile.text(it, buildAdviceMarkdown(displayName, descDefault, advice)) }
        compatNote?.let { files += OutFile.text("$COMPAT_SKILL_DIR/SKILL.md", it) }
        files += OutFile.text(
            ".operit-origin.json",
            buildOriginText(source, pluginId, displayName, version, author, tools, advice, compat, uiEntries, workflowTemplates)
        )
        source.files.forEach { file ->
            val path = "operit/pkg/${file.path}"
            if (!isSafeOutputPath(path)) return@forEach
            files += OutFile(path, file.bytes)
        }
        val zip = zip(files) ?: return OperitConversion.Failed(
            listOf(PluginError(PluginErrorCode.ARCHIVE_TOO_LARGE))
        )
        return OperitConversion.Ready(
            zip = zip,
            report = OperitImportReport(
                format = source.format,
                pluginId = pluginId,
                displayName = displayName,
                toolCount = tools.size,
                adviceCount = advice.size,
                compat = compat,
                workflowTemplateCount = workflowTemplates.size,
                workflowTemplateNames = workflowTemplates.map { it.second ?: it.first }
            )
        )
    }

    private fun convertSkill(source: OperitSource): OperitConversion {
        val displayName = cleanText(source.skillName ?: source.sourceName ?: "Operit Skill", 80)
            .ifEmpty { "Operit Skill" }
        val pluginId = "operit.${slug(source.packageId ?: source.skillName ?: source.sourceName ?: displayName)}"
        val description = cleanText(
            source.skillDescription ?: "由 Operit 技能包导入的命令模板。",
            600
        ).ifEmpty { "Imported from an Operit skill package." }
        val skillId = "commands"
        val skillText = buildSkillMarkdown(displayName, description, source.skillCommands)
        val manifest = buildJsonObject {
            put("manifestVersion", 1)
            put("id", pluginId)
            putJsonObject("name") { put(DEFAULT_KEY, displayName) }
            put("version", FALLBACK_VERSION)
            put("publisher", PUBLISHER)
            put("repository", REPOSITORY)
            putJsonObject("description") { put(DEFAULT_KEY, description) }
            put("license", LICENSE)
            putJsonObject("engines") { put("pluginApi", "^1.0.0") }
            putJsonObject("contributes") {
                putJsonArray("skills") {
                    addJsonObject {
                        put("id", skillId)
                        put("file", "skills/$skillId/SKILL.md")
                    }
                }
            }
        }.toString()
        val compat = OperitCompatReport(emptyList(), 0)
        val files = listOf(
            OutFile.text("plugin.json", manifest),
            OutFile.text("skills/$skillId/SKILL.md", skillText),
            OutFile.text(
                ".operit-origin.json",
                buildOriginText(
                    source, pluginId, displayName, FALLBACK_VERSION, null, emptyList(), emptyList(), compat,
                    emptyList(), emptyList()
                )
            )
        )
        val zip = zip(files) ?: return OperitConversion.Failed(
            listOf(PluginError(PluginErrorCode.ARCHIVE_TOO_LARGE))
        )
        return OperitConversion.Ready(
            zip = zip,
            report = OperitImportReport(
                format = source.format,
                pluginId = pluginId,
                displayName = displayName,
                toolCount = 0,
                adviceCount = 0,
                compat = compat
            )
        )
    }

    private fun buildManifestText(
        pluginId: String,
        displayName: String,
        nameZh: String?,
        nameEn: String?,
        description: String,
        descriptionZh: String?,
        descriptionEn: String?,
        version: String,
        tools: List<ConvertedTool>,
        skills: List<ConvertedSkill>,
        uiEntries: List<ConvertedUiEntry>
    ): String = buildJsonObject {
        put("manifestVersion", 1)
        put("id", pluginId)
        putJsonObject("name") {
            put(DEFAULT_KEY, displayName)
            nameZh?.takeIf { it != displayName }?.let { put("zh-cn", it) }
            nameEn?.takeIf { it != displayName }?.let { put("en", it) }
        }
        put("version", version)
        put("publisher", PUBLISHER)
        put("repository", REPOSITORY)
        putJsonObject("description") {
            put(DEFAULT_KEY, description)
            descriptionZh?.takeIf { it != description && it.isNotEmpty() }?.let { put("zh-cn", it) }
            descriptionEn?.takeIf { it != description && it.isNotEmpty() }?.let { put("en", it) }
        }
        put("license", LICENSE)
        putJsonObject("engines") { put("pluginApi", "^1.0.0") }
        // 面板也要靠插件进程渲染：只有 uiEntries 没有 tools 的包同样需要 runtime，
        // 否则进程起不来，点「打开」只会是一个永远空的面板。
        if (tools.isNotEmpty() || uiEntries.isNotEmpty()) {
            putJsonObject("runtime") {
                put("type", "javascript")
                put("entry", ADAPTER_PATH)
            }
        }
        putJsonObject("contributes") {
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { tool ->
                        addJsonObject {
                            put("id", tool.id)
                            putJsonObject("title") { put(DEFAULT_KEY, tool.title) }
                            putJsonObject("description") { put(DEFAULT_KEY, tool.description) }
                            put("inputSchema", schemaPath(tool.id))
                            put("handler", tool.id)
                        }
                    }
                }
            }
            if (skills.isNotEmpty()) {
                putJsonArray("skills") {
                    skills.forEach { skill ->
                        addJsonObject {
                            put("id", skill.id)
                            put("file", skill.file)
                        }
                    }
                }
            }
            if (uiEntries.isNotEmpty()) {
                putJsonArray("uiEntries") {
                    uiEntries.forEach { entry ->
                        addJsonObject {
                            put("id", entry.id)
                            putJsonObject("title") { put(DEFAULT_KEY, entry.title) }
                            put("slot", PluginUiSlots.DRAWER_FOOTER)
                            put("screen", entry.screen)
                        }
                    }
                }
            }
        }
    }.toString()

    private fun buildSchemaText(tool: ConvertedTool): String = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            tool.parameters.forEach { parameter ->
                putJsonObject(parameter.name) {
                    put("type", parameter.type)
                    if (parameter.description.isNotEmpty()) put("description", parameter.description)
                    defaultValue(parameter.defaultValue)?.let { put("default", it) }
                }
            }
        }
        if (tool.openSchema) put("additionalProperties", true)
        val required = tool.parameters.filter { it.required }.map { it.name }
        if (required.isNotEmpty()) {
            putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        }
    }.toString()

    /** Hjson 解析出的默认值可能是 String / Long / Double / Boolean，统一转成 JSON 标量。 */
    private fun defaultValue(raw: Any?): JsonElement? = when (raw) {
        null -> null
        is Boolean -> JsonPrimitive(raw)
        is Number -> JsonPrimitive(raw)
        is String -> JsonPrimitive(raw)
        is List<*> -> buildJsonArray { raw.forEach { item -> item?.let { add(JsonPrimitive(it.toString())) } } }
        else -> null
    }

    /**
     * 生成入口脚本：宿主按行 JSON-RPC 调用 `tool.invoke`，这里按清单里的 handler 名
     * 找到原包导出的函数（`module.exports[name]` / `exports[name]` / `globalThis[name]`）并调用。
     */
    /**
     * 生成适配层入口。
     *
     * [mainFile] 也进 packages：v2 面板靠主入口里的 `registerUiRoute` 注册，
     * 只有纯面板、没有工具的包如果不 boot 主入口，进程起来后一个路由都没有。
     */
    private fun buildAdapterScript(tools: List<ConvertedTool>, mainFile: String?): String {
        val byFile = tools.groupBy { it.file }.toMutableMap()
        mainFile?.takeIf { it.isNotBlank() }?.let { main ->
            byFile.getOrPut(main) { emptyList() }
        }
        val packages = buildJsonArray {
            byFile.forEach { (file, fileTools) ->
                addJsonObject {
                    put("file", file)
                    putJsonArray("tools") {
                        fileTools.forEach { tool ->
                            addJsonObject {
                                put("name", tool.originalName)
                                put("handler", tool.id)
                                if (tool.runtimeRegistered) put("runtime", true)
                            }
                        }
                    }
                }
            }
        }.toString()
        return ADAPTER_TEMPLATE.replace(PLACEHOLDER_PACKAGES, packages)
    }

    /**
     * 从包内文件里认出工作流模板。
     *
     * 不只看 manifest 的 `workflow_templates` 声明：Operit 的包常把模板直接放在 resources 目录下的 JSON
     * 而不登记，只认声明会漏。判定只看结构（`nodes` / `connections` 数组），
     * 解析不成的静默跳过——包里大部分 JSON 是 schema、面板配置或状态文件。
     */
    private fun collectWorkflowTemplates(files: List<OperitFile>): List<Pair<String, String?>> =
        files.mapNotNull { file ->
            val lower = file.path.lowercase()
            if (!lower.endsWith(".json")) return@mapNotNull null
            val underResources = lower.contains("resources/")
            val namedWorkflow = lower.substringAfterLast('/').contains("workflow")
            if (!underResources && !namedWorkflow) return@mapNotNull null
            if (file.bytes.size > MAX_TEMPLATE_BYTES) return@mapNotNull null
            val text = runCatching { String(file.bytes, Charsets.UTF_8) }.getOrNull()
                ?: return@mapNotNull null
            if (!WorkflowTemplateFormats.looksLikeTemplate(text)) return@mapNotNull null
            file.path to WorkflowTemplateFormats.templateName(text)
        }.distinctBy { it.first }

    private fun buildOriginText(
        source: OperitSource,
        pluginId: String,
        displayName: String,
        version: String,
        author: String?,
        tools: List<ConvertedTool>,
        advice: List<AdviceItem>,
        compat: OperitCompatReport,
        uiEntries: List<ConvertedUiEntry>,
        workflowTemplates: List<Pair<String, String?>>
    ): String {
        val primary = source.scripts.firstNotNullOfOrNull { it.metadata }
        return buildJsonObject {
            put("source", "operit")
            put("format", source.format.name.lowercase())
            put("generator", CONVERTER_ID)
            put("importedAt", System.currentTimeMillis())
            put("pluginId", pluginId)
            put("displayName", displayName)
            put("version", version)
            put("repository", REPOSITORY)
            source.packageId?.let { put("packageId", it) }
            source.apiVersion?.let { put("apiVersion", it) }
            source.sourceName?.let { put("originalName", cleanText(it, 200)) }
            author?.let { put("author", cleanText(it, 120)) }
            source.mainFile?.let { put("main", it) }
            putJsonArray("scripts") { source.scripts.forEach { add(JsonPrimitive(it.path)) } }
            putJsonArray("env") { primary?.env.orEmpty().forEach { add(JsonPrimitive(it)) } }
            putJsonArray("tools") {
                tools.forEach { tool ->
                    addJsonObject {
                        put("id", tool.id)
                        put("originalName", tool.originalName)
                        put("file", tool.file)
                    }
                }
            }
            putJsonArray("advice") { advice.forEach { add(JsonPrimitive(it.name)) } }
            if (workflowTemplates.isNotEmpty()) {
                putJsonArray("workflowTemplates") {
                    workflowTemplates.forEach { (path, name) ->
                        addJsonObject {
                            put("file", path)
                            name?.let { put("name", it) }
                        }
                    }
                }
            }
            if (uiEntries.isNotEmpty()) {
                putJsonArray("uiEntries") {
                    uiEntries.forEach { entry ->
                        addJsonObject {
                            put("id", entry.id)
                            put("title", entry.title)
                            put("screen", entry.screen)
                        }
                    }
                }
            }
            put("compat", compat.toJson())
        }.toString()
    }

    /** 提示型工具转成技能：内容就是原本注入给模型的那段说明。 */
    private fun buildAdviceMarkdown(
        displayName: String,
        description: String,
        advice: List<AdviceItem>
    ): String {
        val builder = StringBuilder()
        builder.append("---\n")
        builder.append("description: ").append(description.replace("\n", " ")).append('\n')
        builder.append("---\n\n")
        builder.append("以下规则来自「").append(displayName).append("」的提示型工具，本身不执行代码，直接照说明使用。\n\n")
        advice.forEach { item ->
            builder.append("## ").append(item.name).append('\n')
            if (item.description.isNotEmpty()) builder.append(item.description).append("\n")
            builder.append('\n')
        }
        return builder.toString().trimEnd() + "\n"
    }

    /** 没有可表达能力时的说明技能；列出宿主暂不支持的声明，不当作导入失败。 */
    private fun buildCompatNote(
        displayName: String,
        description: String,
        compat: OperitCompatReport
    ): String {
        val unsupported = compat.unavailable.map { it.call }.distinct().take(8)
        val builder = StringBuilder()
        builder.append("---\n")
        builder.append("description: ").append(cleanText(description, 200)).append('\n')
        builder.append("---\n\n")
        builder.append("# ").append(displayName).append('\n')
        builder.append("这个 Operit 包没有可执行工具或可直接展示的面板，导入后只保留这份说明与原包文件。\n")
        if (unsupported.isNotEmpty()) {
            builder.append("\n宿主暂未支持的声明：\n")
            unsupported.forEach { builder.append("- `").append(it).append("`\n") }
        }
        builder.append("\n原包内容保留在 `operit/pkg/` 下，便于对照排查。\n")
        return builder.toString()
    }

    private fun buildSkillMarkdown(
        displayName: String,
        description: String,
        commands: List<OperitSkillCommand>
    ): String {
        val builder = StringBuilder()
        builder.append("---\n")
        builder.append("description: ").append(description.replace("\n", " ")).append('\n')
        builder.append("---\n\n")
        builder.append("以下是「").append(displayName).append("」从 Operit 技能包导入的命令模板。")
        builder.append("需要时在容器终端执行对应命令，参数自行替换。\n\n")
        commands.forEach { command ->
            builder.append("## ").append(command.name.ifEmpty { "命令" }).append('\n')
            if (command.description.isNotEmpty()) {
                builder.append(command.description).append("\n\n")
            }
            builder.append("```bash\n").append(command.template).append("\n```\n\n")
        }
        return builder.toString().trimEnd() + "\n"
    }

    private class ConvertedTool(
        val id: String,
        val originalName: String,
        val file: String,
        val title: String,
        val description: String,
        val parameters: List<OperitToolParameter>,
        /** 来自 `ToolPkg.registerTool({...})` 的声明，调用时需先让模块跑一遍注册。 */
        val runtimeRegistered: Boolean = false,
        /** 声明里没给参数表（如 registerApi），schema 要允许任意键。 */
        val openSchema: Boolean = false
    )

    private class AdviceItem(val name: String, val description: String)

    private class ConvertedSkill(val id: String, val file: String)

    /** 已转成宿主清单形态的面板入口；[screen] 已带 `operit/pkg/` 前缀。 */
    private class ConvertedUiEntry(val id: String, val title: String, val screen: String)

    private class OutFile(val path: String, val bytes: ByteArray) {
        companion object {
            fun text(path: String, text: String) = OutFile(path, text.toByteArray(Charsets.UTF_8))
        }
    }

    private fun zip(files: List<OutFile>): ByteArray? {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            files.forEach { file ->
                if (file.bytes.size > MAX_ENTRY_BYTES) return null
                zip.putNextEntry(ZipEntry(file.path))
                zip.write(file.bytes)
                zip.closeEntry()
            }
        }
        val bytes = buffer.toByteArray()
        return if (bytes.size > MAX_ARCHIVE_BYTES) null else bytes
    }

    private fun schemaPath(toolId: String): String = "schemas/$toolId.input.json"

    private fun isSafeOutputPath(path: String): Boolean =
        !path.startsWith("/") && !path.contains("..") && !path.contains('\\') &&
            path.split('/').none { it.isEmpty() } && path.length <= 200 && path.split('/').size <= 8

    private fun cleanText(raw: String?, max: Int): String {
        val text = raw.orEmpty().replace('\r', ' ').replace('\n', ' ').trim()
        val filtered = text.filterNot { it.isISOControl() }
        return if (filtered.length > max) filtered.take(max) else filtered
    }

    private fun uniqueId(base: String, used: MutableSet<String>): String {
        if (used.add(base)) return base
        var index = 2
        while (true) {
            val candidate = "${base}_$index"
            if (used.add(candidate)) return candidate
            index++
        }
    }

    /** 局部 ID 只接受 `[A-Za-z][A-Za-z0-9_-]{0,63}`，中文与空格统一换成下划线。 */
    private fun sanitizeLocalId(raw: String): String {
        val cleaned = buildString {
            raw.forEach { c ->
                val ascii = c.code < 128
                append(if ((ascii && c.isLetterOrDigit()) || c == '_' || c == '-') c else '_')
            }
        }.trim('_', '-')
        val head = if (cleaned.isEmpty() || !cleaned.first().isLetter()) "tool_$cleaned" else cleaned
        return head.take(48).trim('_', '-').ifEmpty { "tool" }
    }

    /** 插件 ID 段只接受 `[a-z][a-z0-9-]{0,31}`。 */
    private fun slug(raw: String): String {
        val cleaned = buildString {
            raw.lowercase().forEach { c ->
                append(if (c in 'a'..'z' || c in '0'..'9') c else '-')
            }
        }
        val collapsed = cleaned.split('-').filter { it.isNotEmpty() }.joinToString("-")
        val trimmed = collapsed.take(31).trim('-')
        if (trimmed.isEmpty()) return "package"
        return if (trimmed.first() in 'a'..'z') trimmed else "p-$trimmed"
    }

    private fun normalizeVersion(raw: String?): String {
        val text = raw?.trim().orEmpty().removePrefix("v").removePrefix("V")
        val match = Regex("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:-([0-9A-Za-z.-]+))?").find(text)
            ?: return FALLBACK_VERSION
        val major = match.groupValues[1]
        val minor = match.groupValues[2].ifEmpty { "0" }
        val patch = match.groupValues[3].ifEmpty { "0" }
        val pre = match.groupValues[4]
        val candidate = if (pre.isEmpty()) "$major.$minor.$patch" else "$major.$minor.$patch-$pre"
        return SemVer.parse(candidate)?.toString() ?: FALLBACK_VERSION
    }

    companion object {
        const val ADAPTER_PATH = "operit/main.js"
        private const val TAG = "OperitConverter"
        private const val MAX_TEMPLATE_BYTES = 512 * 1024
        private const val MAX_TOOLS_PER_PLUGIN = 64
        private const val ADVICE_SKILL_ID = "advice"
        private const val ADVICE_SKILL_DIR = "skills/$ADVICE_SKILL_ID"
        private const val COMPAT_SKILL_ID = "compat"
        private const val COMPAT_SKILL_DIR = "skills/$COMPAT_SKILL_ID"
        private const val CONVERTER_ID = "aharou-operit-importer/1"
        private const val MAX_ARCHIVE_BYTES = 15L * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 4L * 1024 * 1024
        private const val PUBLISHER = "operit"
        private const val REPOSITORY = "https://github.com/AAswordman/Operit"
        private const val LICENSE = "Unspecified"
        private const val DEFAULT_KEY = "default"
        private const val FALLBACK_VERSION = "1.0.0"
        private const val PLACEHOLDER_PACKAGES = "__PACKAGES__"

        private val ADAPTER_TEMPLATE = """'use strict';
// 由 Aharou 的 Operit 导入器生成：把 Operit 工具函数接到宿主的 JSON-RPC 上。
const path = require('path');
const readline = require('readline');

const packages = __PACKAGES__;

let hostSeq = 0;
const hostPending = new Map();

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n');
}

function hostCall(method, params) {
  const id = 'host-' + (++hostSeq);
  return new Promise((resolve, reject) => {
    hostPending.set(id, { resolve: resolve, reject: reject });
    send({ jsonrpc: '2.0', id: id, method: method, params: params || {} });
  });
}

globalThis.__aharouHostCall = hostCall;

// shim 由宿主按环境变量注入。不能只 require：shim 在模块模式只导出工厂，
// 必须显式 createRuntime + installGlobals 才会给全局补上 Tools / complete。
// 它自带的 stdio 传输会再开一个 stdin 读循环抢消息，所以换成复用本适配层通道的 transport。
let shimRuntime = null;
let shimInvoke = null;
if (process.env.AHAROU_OPERIT_SHIM) {
  try {
    const shim = require(process.env.AHAROU_OPERIT_SHIM);
    const shimTransport = {
      request: function (method, params) { return hostCall(method, params); },
      onRequest: function (handler) { shimInvoke = handler; },
      onClose: function () {},
      sendResult: function (id, result) {
        send({ jsonrpc: '2.0', id: id, result: result === undefined ? null : result });
      },
      sendError: function (id, error) { send({ jsonrpc: '2.0', id: id, error: error }); },
      notify: function (method, params) {
        send({ jsonrpc: '2.0', method: method, params: params || {} });
      },
      close: function () {}
    };
    shimRuntime = shim.createRuntime({ transport: shimTransport });
    shim.installGlobals(shimRuntime);
  } catch (error) {
    globalThis.__aharouShimError = String((error && error.message) || error);
  }
}

// Operit 的工具包入口会在加载时调 registerToolPkg 注册 UI / hook；
// 宿主不跑那套容器，这里给个空实现，避免整个模块加载失败
if (typeof globalThis.registerToolPkg !== 'function') {
  globalThis.registerToolPkg = function () { return undefined; };
}

if (!globalThis.Tools) {
  globalThis.Tools = new Proxy({}, {
    get: function (_target, domain) {
      return new Proxy({}, {
        get: function (_inner, method) {
          return function () {
            const detail = globalThis.__aharouShimError ? ': ' + globalThis.__aharouShimError : '';
            throw new Error('OPERIT_TOOLS_UNAVAILABLE Tools.' + String(domain) + '.' + String(method) + detail);
          };
        }
      });
    }
  });
}

// 少数包用 ToolPkg.registerTool 在 JS 里声明工具；宿主没有这套运行时，
// 这里补一个注册入口，让声明不抛错，并把定义按名字存起来供 invoke 时查找。
const registeredTools = new Map();
if (!globalThis.ToolPkg) globalThis.ToolPkg = {};
if (typeof globalThis.ToolPkg.registerTool !== 'function') {
  globalThis.ToolPkg.registerTool = function (definition) {
    if (!definition || typeof definition !== 'object') return;
    const name = definition.name;
    if (name) registeredTools.set(String(name), definition);
  };
}
// 宿主已经提供的 registerApi 要保留（它会把函数登记进 shim 的 handler 表），
// 但同时也记一份到本地：适配层自处理 invoke 时用它查函数。
const shimRegisterApi = typeof globalThis.ToolPkg.registerApi === 'function'
  ? globalThis.ToolPkg.registerApi
  : null;
globalThis.ToolPkg.registerApi = function (definition) {
  if (definition && typeof definition === 'object' && definition.name) {
    registeredTools.set(String(definition.name), definition);
  }
  if (shimRegisterApi) return shimRegisterApi(definition);
  return undefined;
};

const modules = new Map();

function loadModule(relative) {
  if (modules.has(relative)) return modules.get(relative);
  let loaded;
  try {
    loaded = { module: require(path.join(__dirname, 'pkg', relative)) };
  } catch (error) {
    loaded = { error: error };
  }
  modules.set(relative, loaded);
  return loaded;
}

function resolveFunction(relative, name) {
  const loaded = loadModule(relative);
  if (loaded.error) {
    const message = String((loaded.error && loaded.error.message) || loaded.error);
    throw new Error('OPERIT_MODULE_LOAD_FAILED ' + relative + ': ' + message);
  }
  const exported = loaded.module;
  if (exported && typeof exported[name] === 'function') return exported[name];
  if (exported && exported.default && typeof exported.default[name] === 'function') return exported.default[name];
  if (typeof globalThis[name] === 'function') return globalThis[name];
  return null;
}

// 运行时注册的工具不在 exports 上，得等模块自己的 registerToolPkg 跑过才登记进 registeredTools；
// 每个模块只启一次，避免重复注册或插件副作用叠几遍。
const bootedEntries = new Set();

function resolveRegistered(relative, name) {
  const loaded = loadModule(relative);
  if (loaded.error) return null;
  const entry = loaded.module;
  if (entry && typeof entry.registerToolPkg === 'function' && !bootedEntries.has(relative)) {
    bootedEntries.add(relative);
    try {
      entry.registerToolPkg();
    } catch (error) {
      // 注册入口可能依赖没有的宿主能力；抛错不影响模块加载时已登记的声明
    }
  }
  const definition = registeredTools.get(name);
  if (definition) {
    if (typeof definition === 'function') return definition;
    const candidates = [definition.execute, definition.exec, definition.handler, definition.function, definition.invoke];
    for (let index = 0; index < candidates.length; index += 1) {
      if (typeof candidates[index] === 'function') return candidates[index];
    }
  }
  return null;
}

// 先让每个模块的 registerToolPkg 跑一遍，registerApi 才会把真实实现写进 shim 的表。
function bootEntry(relative) {
  const loaded = loadModule(relative);
  if (loaded.error) return;
  const entry = loaded.module;
  if (!entry || typeof entry.registerToolPkg !== 'function') return;
  if (bootedEntries.has(relative)) return;
  bootedEntries.add(relative);
  try {
    entry.registerToolPkg();
  } catch (error) {
    // 注册入口可能用到没实现的宿主能力；抛错不影响模块加载时已登记的声明
  }
}

packages.forEach(function (pkg) { bootEntry(pkg.file); });

// v2 工具是 registerApi 注册的，函数已经在 shim 表里。必须先拍快照再 serve：
// serve 会把下面这些包装写进同一张表并覆盖同名项，覆盖之后再 getHandler 拿到的是包装本身，
// 包装又去查表 —— 实测直接 RangeError: Maximum call stack size exceeded。
const shimApis = new Map();
if (shimRuntime && typeof shimRuntime.getHandler === 'function') {
  packages.forEach(function (pkg) {
    pkg.tools.forEach(function (tool) {
      if (shimApis.has(tool.handler)) return;
      const registered = shimRuntime.getHandler(tool.name);
      if (typeof registered === 'function') shimApis.set(tool.handler, registered);
    });
  });
}

const handlers = {};
packages.forEach(function (pkg) {
  pkg.tools.forEach(function (tool) {
    // registerApi 注册的实现优先：它已经是插件自己的函数，外面再套一层只会多一次调用栈。
    const registered = shimApis.get(tool.handler);
    if (registered) {
      handlers[tool.handler] = registered;
      return;
    }
    handlers[tool.handler] = function (args) {
      const fn = resolveFunction(pkg.file, tool.name) || resolveRegistered(pkg.file, tool.name);
      if (!fn) throw new Error('HANDLER_NOT_FOUND ' + pkg.file + '#' + tool.name);
      return fn(args || {});
    };
  });
});

// 交给 shim 自己分派：它会把工具函数包在调用上下文里跑，并取 complete() 的值作结果回。
// 适配层不再自包一层，否则 complete 拿不到当前调用，会报或丢失结果。
if (shimRuntime && typeof shimRuntime.serve === 'function') {
  shimRuntime.serve(handlers);
}

function handleRequest(id, method, params) {
  // shim 接管分派后，一切 invoke 都交给它：它认得 tool/hook/ui 三种入参形状，
  // 并负责把 complete({success:false}) 翻成 JSON-RPC error。
  if (shimInvoke) {
    shimInvoke(method, params, id);
    return;
  }
  if (method !== 'tool.invoke' && method !== 'hook.invoke' && method !== 'ui.invoke') {
    send({ jsonrpc: '2.0', id: id, error: { code: 'METHOD_UNKNOWN', message: method } });
    return;
  }
  const handler = handlers[params.handler];
  if (typeof handler !== 'function') {
    send({ jsonrpc: '2.0', id: id, error: { code: 'HANDLER_NOT_FOUND', message: params.handler } });
    return;
  }
  Promise.resolve()
    .then(function () { return handler(params.args || {}); })
    .then(function (result) {
      send({ jsonrpc: '2.0', id: id, result: result === undefined ? null : result });
    })
    .catch(function (error) {
      send({
        jsonrpc: '2.0',
        id: id,
        error: { code: 'PLUGIN_ERROR', message: String((error && error.message) || error) }
      });
    });
}

readline.createInterface({ input: process.stdin }).on('line', function (line) {
  if (!line.trim()) return;
  let message;
  try {
    message = JSON.parse(line);
  } catch (error) {
    return;
  }
  if (message.method && message.id !== undefined) {
    handleRequest(message.id, message.method, message.params || {});
  } else if (message.method) {
    return;
  } else if (message.id !== undefined) {
    const pending = hostPending.get(String(message.id));
    if (!pending) return;
    hostPending.delete(String(message.id));
    if (message.error) pending.reject(new Error(message.error.message || 'host error'));
    else pending.resolve(message.result === undefined ? null : message.result);
  }
});
"""
    }
}
