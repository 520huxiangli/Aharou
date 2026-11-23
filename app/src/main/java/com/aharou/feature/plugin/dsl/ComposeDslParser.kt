/*
 * 移植自 AAswordman/Operit（原文件 core/tools/packTool/ToolPkgComposeDslParser.kt，LGPL-3.0）。
 * 本文件随 Aharou 以 GPL-3.0 分发。
 *
 * 改动：
 * - object 名 ToolPkgComposeDslParser → ComposeDslParser，节点类型改用 ComposeDslNode；
 * - 逻辑与容错分支原样保留（字符串里再包一层 JSON、children 允许单对象、slots 允许列表或对象）。
 */

package com.aharou.feature.plugin.dsl

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

object ComposeDslParser {
    fun parseRenderResult(rawResult: Any?): ComposeDslRenderResult? {
        val root = parseRootObject(rawResult) ?: return null
        val treeNode = parseNode(root.opt("tree")) ?: return null
        return ComposeDslRenderResult(
            tree = treeNode,
            state = asMap(root.opt("state")),
            memo = asMap(root.opt("memo")),
            actionResult = if (root.has("actionResult")) jsonToPlainValue(root.opt("actionResult")) else null
        )
    }

    /** 取出 prop 里被替换掉的 actionId；点击时把它原样回传给 JS。 */
    fun extractActionId(value: Any?): String? {
        return when (value) {
            is JSONObject -> value.optString("__actionId").trim().ifBlank { null }
            is Map<*, *> -> value["__actionId"]?.toString()?.trim()?.ifBlank { null }
            is String -> {
                val normalized = value.trim()
                when {
                    normalized.startsWith("__action:") ->
                        normalized.removePrefix("__action:").trim().ifBlank { null }
                    normalized.isNotBlank() -> normalized
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun parseRootObject(rawResult: Any?): JSONObject? {
        return when (rawResult) {
            is JSONObject -> rawResult
            is String -> parseRootObjectFromString(rawResult)
            else -> parseRootObjectFromString(rawResult?.toString().orEmpty())
        }
    }

    private fun parseRootObjectFromString(raw: String): JSONObject? {
        val trimmed = raw.trim()
        if (trimmed.isBlank() || trimmed.equals("null", ignoreCase = true)) {
            return null
        }

        val first = runCatching { JSONTokener(trimmed).nextValue() }.getOrNull() ?: return null
        if (first is JSONObject) {
            return first
        }

        if (first is String) {
            val nested = first.trim()
            if (nested.startsWith("{") && nested.endsWith("}")) {
                return runCatching { JSONObject(nested) }.getOrNull()
            }
        }

        return null
    }

    private fun parseNode(value: Any?): ComposeDslNode? {
        val nodeObj =
            when (value) {
                is JSONObject -> value
                is Map<*, *> -> JSONObject(value)
                else -> return null
            }

        val type = nodeObj.optString("type").trim()
        if (type.isBlank()) {
            return null
        }

        val props = asMap(nodeObj.opt("props"))
        val children = mutableListOf<ComposeDslNode>()
        val slots = linkedMapOf<String, List<ComposeDslNode>>()

        val rawChildren = nodeObj.opt("children")
        when (rawChildren) {
            is JSONArray -> {
                for (index in 0 until rawChildren.length()) {
                    parseNode(rawChildren.opt(index))?.let { children.add(it) }
                }
            }
            is List<*> -> {
                rawChildren.forEach { child ->
                    parseNode(child)?.let { children.add(it) }
                }
            }
            is JSONObject -> {
                parseNode(rawChildren)?.let { children.add(it) }
            }
        }

        val rawSlots = nodeObj.opt("slots")
        when (rawSlots) {
            is JSONObject -> {
                rawSlots.keys().forEach { slotName ->
                    val slotChildren = mutableListOf<ComposeDslNode>()
                    when (val rawSlotValue = rawSlots.opt(slotName)) {
                        is JSONArray -> {
                            for (index in 0 until rawSlotValue.length()) {
                                parseNode(rawSlotValue.opt(index))?.let { slotChildren.add(it) }
                            }
                        }
                        is List<*> -> {
                            rawSlotValue.forEach { child ->
                                parseNode(child)?.let { slotChildren.add(it) }
                            }
                        }
                        is JSONObject -> {
                            parseNode(rawSlotValue)?.let { slotChildren.add(it) }
                        }
                    }
                    if (slotChildren.isNotEmpty()) {
                        slots[slotName] = slotChildren
                    }
                }
            }
            is Map<*, *> -> {
                rawSlots.entries.forEach { (slotName, rawSlotValue) ->
                    val normalizedSlotName = slotName?.toString()?.trim().orEmpty()
                    if (normalizedSlotName.isBlank()) {
                        return@forEach
                    }
                    val slotChildren = mutableListOf<ComposeDslNode>()
                    when (rawSlotValue) {
                        is List<*> -> {
                            rawSlotValue.forEach { child ->
                                parseNode(child)?.let { slotChildren.add(it) }
                            }
                        }
                        is Map<*, *> -> {
                            parseNode(rawSlotValue)?.let { slotChildren.add(it) }
                        }
                    }
                    if (slotChildren.isNotEmpty()) {
                        slots[normalizedSlotName] = slotChildren
                    }
                }
            }
        }

        return ComposeDslNode(
            type = type,
            props = props,
            children = children,
            slots = slots
        )
    }

    /** JSON 结构转成渲染层用的 Map/List，actionResult 就是这么被 WebView 回调消费的。 */
    private fun jsonToPlainValue(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().associateWith { jsonToPlainValue(value.opt(it)) }
        is JSONArray -> (0 until value.length()).map { jsonToPlainValue(value.opt(it)) }
        else -> value
    }

    private fun asMap(value: Any?): Map<String, Any?> {
        return when (value) {
            is JSONObject -> {
                val map = linkedMapOf<String, Any?>()
                value.keys().forEach { key ->
                    map[key] = normalize(value.opt(key))
                }
                map
            }
            is Map<*, *> -> {
                value.entries.associate { entry ->
                    entry.key.toString() to normalize(entry.value)
                }
            }
            else -> emptyMap()
        }
    }

    private fun asList(value: Any?): List<Any?> {
        return when (value) {
            is JSONArray -> {
                buildList {
                    for (index in 0 until value.length()) {
                        add(normalize(value.opt(index)))
                    }
                }
            }
            is List<*> -> value.map { normalize(it) }
            else -> emptyList()
        }
    }

    private fun normalize(value: Any?): Any? {
        return when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> asMap(value)
            is JSONArray -> asList(value)
            else -> value
        }
    }
}
