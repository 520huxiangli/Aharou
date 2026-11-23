package com.aharou.feature.plugin.data.import

/** 一个工具参数的声明；类型只保留 JSON Schema 的一级类型，[defaultValue] 原样带进 schema。 */
data class OperitToolParameter(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String,
    val defaultValue: Any? = null
)

/**
 * 元数据里声明的一个工具函数。
 * [advice] 为 true 时这条不是可执行函数，只是一段给模型的提示词（Operit 里由引擎注入），
 * 转换时改成技能而不是运行时工具。
 */
data class OperitToolDecl(
    val name: String,
    val displayName: String?,
    val description: String,
    val parameters: List<OperitToolParameter>,
    val advice: Boolean = false
)

/** 一段 JS 头部 METADATA 块解析出的信息。 */
data class OperitMetadata(
    val name: String,
    val displayName: String?,
    val displayNameZh: String?,
    val displayNameEn: String?,
    val descriptionZh: String?,
    val descriptionEn: String?,
    val descriptionDefault: String?,
    val version: String?,
    val author: String?,
    val env: List<String>,
    val tools: List<OperitToolDecl>
)

/**
 * 文本字段可能是字符串、`{zh, en}`，也可能嵌套 `{default: ...}`。
 * 元数据与 toolpkg 清单共用这套取值规则。
 */
internal fun operitLocalizedText(raw: Any?, language: String? = null): String? {
    when (raw) {
        is String -> return raw.trim().takeIf { it.isNotEmpty() }
        is Map<*, *> -> {
            if (language == null) {
                listOf("default", "zh", "en").forEach { key ->
                    (raw[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
                }
                return null
            }
            (raw[language] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            if (language == "zh") {
                (raw["zh-cn"] as? String ?: raw["zh-CN"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { return it }
            }
            return null
        }
        else -> return null
    }
}

/**
 * 读取 JS 头部「斜杠加星号 METADATA」块。
 *
 * 解析逻辑参考 AAswordman/Operit（LGPL-3.0）的工具元数据约定，改动：
 * 只取宿主需要的字段，缺字段时用默认值而不是整份丢弃（`name` 除外）。
 * 实测社区包里 `display_name` / `description` 既可能是字符串也可能是 `{zh, en}`，两种都收。
 */
object OperitMetadataReader {

    private val BLOCK = Regex("/\\*\\s*METADATA\\b([\\s\\S]*?)\\*/")

    /** 文件里第一个非空的 METADATA 块；没有则返回 null。 */
    fun findBlock(script: String): String? {
        return BLOCK.findAll(script)
            .map { it.groupValues[1].trim() }
            .firstOrNull { it.isNotEmpty() }
    }

    fun read(script: String, fallbackName: String): OperitMetadata? {
        val block = findBlock(script) ?: return null
        val raw = try {
            OperitHjson.parseObject(block)
        } catch (e: OperitHjson.OperitHjsonException) {
            return null
        } catch (e: RuntimeException) {
            return null
        }
        val name = raw.string("name")?.takeIf { it.isNotBlank() } ?: return null
        val description = raw["description"]
        val displayName = raw["display_name"] ?: raw["displayName"]
        val parameters = readParameters(raw["parameters"])
        val declaredTools = readTools(raw["tools"])
        val tools = when {
            declaredTools.isNotEmpty() -> declaredTools
            // 单个工具文件通常把 name / parameters 写在顶层，没有 tools 数组
            parameters.isNotEmpty() || raw["parameters"] != null -> listOf(
                OperitToolDecl(
                    name = name,
                    displayName = text(displayName, "zh") ?: text(displayName, "en"),
                    description = text(description).orEmpty(),
                    parameters = parameters,
                    advice = raw["advice"] == true
                )
            )
            else -> emptyList()
        }
        return OperitMetadata(
            name = name,
            displayName = text(displayName),
            displayNameZh = text(displayName, "zh"),
            displayNameEn = text(displayName, "en"),
            descriptionZh = text(description, "zh"),
            descriptionEn = text(description, "en"),
            descriptionDefault = text(description),
            version = raw.string("version"),
            author = raw.string("author"),
            env = readEnv(raw["env"]),
            tools = tools
        )
    }

    private fun readTools(raw: Any?): List<OperitToolDecl> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val name = map.string("name")?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            OperitToolDecl(
                name = name,
                displayName = text(map["display_name"] ?: map["displayName"]),
                description = text(map["description"]).orEmpty(),
                parameters = readParameters(map["parameters"]),
                advice = map["advice"] == true
            )
        }
    }

    private fun readParameters(raw: Any?): List<OperitToolParameter> = operitParametersFrom(raw)

    private fun readEnv(raw: Any?): List<String> {
        val list = raw as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            when (item) {
                is String -> item.trim().takeIf { it.isNotEmpty() }
                is Map<*, *> -> item.string("name")?.trim()?.takeIf { it.isNotEmpty() }
                else -> null
            }
        }
    }

    /** 文本字段可能是字符串、`{zh, en}`，也可能嵌套 `{default: ...}`。 */
    private fun text(raw: Any?, language: String? = null): String? = operitLocalizedText(raw, language)

    private fun Map<*, *>.string(key: String): String? =
        (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * 参数既可能写成数组，也可能写成「名字 → 声明」的对象，两种都收。
 * METADATA 块与 `ToolPkg.registerTool({...})` 的字面量共用这套规则。
 */
internal fun operitParametersFrom(raw: Any?): List<OperitToolParameter> {
    val result = mutableListOf<OperitToolParameter>()
    when (raw) {
        is List<*> -> raw.forEach { item ->
            val map = item as? Map<*, *> ?: return@forEach
            val name = (map["name"] as? String)?.trim().orEmpty()
            if (name.isEmpty()) return@forEach
            result += operitParameter(name, map)
        }
        is Map<*, *> -> raw.forEach { (key, value) ->
            val name = (key as? String)?.trim().orEmpty()
            if (name.isEmpty()) return@forEach
            result += operitParameter(name, value as? Map<*, *> ?: emptyMap<Any?, Any?>())
        }
    }
    return result
}

private fun operitParameter(name: String, map: Map<*, *>): OperitToolParameter {
    val type = when ((map["type"] as? String)?.trim()?.lowercase()) {
        "number", "integer", "float", "double" -> "number"
        "boolean", "bool" -> "boolean"
        "array" -> "array"
        "object" -> "object"
        else -> "string"
    }
    // 缺省视为必填：Operit 侧不写 required 时工具作者期待的就是「要传」
    val required = when (val value = map["required"]) {
        null -> true
        is Boolean -> value
        is String -> value != "false"
        else -> true
    }
    val description = (map["description"] as? String)?.trim().orEmpty()
    return OperitToolParameter(name, type, required, description, map["default"])
}
