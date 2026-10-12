package com.aharou.feature.agent.domain.common

import com.aharou.core.util.FileLogger
import com.aharou.core.util.writeTextSafely
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.putJsonArray

/**
 * 「禁用名单」JSON 配置的读写，格式 `{"disabled": ["a", "b"]}`，供技能与子代理共用。
 *
 * [tag] / [describe] 仅用于日志（两处调用方的 TAG 与中文描述不同）；读失败返回空集、
 * 写用临时文件 + rename 原子落盘的行为完全一致。名单里已不存在的名字由调用方过滤时天然忽略。
 */
internal object DisabledNamesStore {
    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    private val PRETTY_JSON = Json { prettyPrint = true }

    fun parse(raw: String, tag: String, describe: String): Set<String> {
        val root = runCatching { JSON.parseToJsonElement(raw).jsonObject }.getOrElse {
            FileLogger.w(tag, "$describe JSON 解析失败: ${it.message}")
            return emptySet()
        }
        return (root["disabled"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?.toSet()
            ?: emptySet()
    }

    fun serialize(names: Set<String>): String {
        val root = buildJsonObject {
            putJsonArray("disabled") { names.sorted().forEach { add(it) } }
        }
        return PRETTY_JSON.encodeToString(JsonObject.serializer(), root)
    }

    fun read(file: File, tag: String, describe: String): Set<String> {
        if (!file.isFile) return emptySet()
        return runCatching { parse(file.readText(), tag, describe) }.getOrElse {
            FileLogger.w(tag, "读取 ${file.name} 失败: ${it.message}")
            emptySet()
        }
    }

    fun write(file: File, names: Set<String>, tag: String) {
        file.parentFile?.mkdirs()
        val json = serialize(names)
        // 临时文件 + rename 原子落盘，避免写一半崩溃损坏配置
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeTextSafely(json, tag)
        if (!tmp.renameTo(file)) {
            // rename 失败（罕见），回退直接写，避免丢配置
            file.writeTextSafely(json, tag)
        }
    }
}
