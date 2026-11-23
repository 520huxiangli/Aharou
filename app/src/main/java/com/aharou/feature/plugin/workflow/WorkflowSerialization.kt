package com.aharou.feature.plugin.workflow

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 工作流的 JSON 编解码。
 *
 * 移植自 Operit（`AAswordman/Operit`，LGPL-3.0）的 `data.model` 多态序列化约定：
 * 节点与参数值用 `__type` 判别，取值是 Kotlin 全限定类名（如
 * `com.ai.assistance.operit.data.model.TriggerNode`）。差异在于**容错**：
 * 未知节点类型落成 [PlaceholderNode]、未知参数类型落成 [ParameterValue.Unsupported]，
 * 不抛异常——一份模板里有新版本节点时，整条工作流仍要能加载、能报出具体哪个节点不支持。
 */

/** 读写磁盘用：格式化输出，方便主人直接看文件。 */
internal val WorkflowStorageJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = true
}

/** 解析模板与工具入参用：紧凑、宽容。 */
internal val WorkflowParseJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
}

/** 节点类型判别：`__type` 优先，缺省时退回 Operit 的 `type` 短名（trigger/execute/...）。 */
internal object WorkflowNodeSerializer : KSerializer<WorkflowNode> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.aharou.feature.plugin.workflow.WorkflowNode")

    override fun deserialize(decoder: Decoder): WorkflowNode {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("WorkflowNode 只能从 JSON 反序列化")
        val obj = input.decodeJsonElement().jsonObject
        val declaredType = (obj["__type"] as? JsonPrimitive)?.contentOrNull
        val legacyType = (obj["type"] as? JsonPrimitive)?.contentOrNull
        val kind = simpleTypeName(declaredType?.takeIf { it.isNotBlank() } ?: legacyType)
        return when (kind) {
            "TriggerNode", "trigger" -> input.json.decodeFromJsonElement(TriggerNode.serializer(), obj)
            "ExecuteNode", "execute" -> input.json.decodeFromJsonElement(ExecuteNode.serializer(), obj)
            "ExtractNode", "extract" -> input.json.decodeFromJsonElement(ExtractNode.serializer(), obj)
            "ConditionNode", "condition" -> input.json.decodeFromJsonElement(ConditionNode.serializer(), obj)
            "LogicNode", "logic" -> input.json.decodeFromJsonElement(LogicNode.serializer(), obj)
            else -> decodePlaceholder(obj, declaredType ?: legacyType.orEmpty())
        }
    }

    private fun decodePlaceholder(obj: JsonObject, originalType: String): PlaceholderNode {
        val rawId = (obj["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return PlaceholderNode(
            // 缺 id 的节点用内容哈希兜底：同一份 JSON 每次加载得到同一个 id，
            // 连接关系不会因为重新加载而错位。
            id = rawId.ifBlank { "auto_" + obj.hashCode().toUInt().toString(16) },
            name = (obj["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            description = (obj["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            position = runCatching {
                WorkflowParseJson.decodeFromJsonElement(NodePosition.serializer(), obj["position"] ?: JsonObject(emptyMap()))
            }.getOrDefault(NodePosition()),
            originalType = originalType,
            raw = obj,
        )
    }

    override fun serialize(encoder: Encoder, value: WorkflowNode) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("WorkflowNode 只能序列化成 JSON")
        val element: JsonObject = when (value) {
            is TriggerNode -> output.json.encodeToJsonElement(TriggerNode.serializer(), value).jsonObject
            is ExecuteNode -> output.json.encodeToJsonElement(ExecuteNode.serializer(), value).jsonObject
            is ExtractNode -> output.json.encodeToJsonElement(ExtractNode.serializer(), value).jsonObject
            is ConditionNode -> output.json.encodeToJsonElement(ConditionNode.serializer(), value).jsonObject
            is LogicNode -> output.json.encodeToJsonElement(LogicNode.serializer(), value).jsonObject
            is PlaceholderNode -> if (value.raw.isNotEmpty()) {
                value.raw
            } else {
                output.json.encodeToJsonElement(PlaceholderNode.serializer(), value).jsonObject
            }
        }
        val typed = if (element.containsKey("__type")) {
            element
        } else {
            JsonObject(element + ("__type" to JsonPrimitive(typeNameOf(value))))
        }
        output.encodeJsonElement(typed)
    }

    private fun typeNameOf(node: WorkflowNode): String = when (node) {
        is TriggerNode -> TriggerNode.serializer().descriptor.serialName
        is ExecuteNode -> ExecuteNode.serializer().descriptor.serialName
        is ExtractNode -> ExtractNode.serializer().descriptor.serialName
        is ConditionNode -> ConditionNode.serializer().descriptor.serialName
        is LogicNode -> LogicNode.serializer().descriptor.serialName
        is PlaceholderNode -> node.originalType.ifBlank { "unknown" }
    }
}

/** 参数值判别；模板里若直接写字符串（而不是 `{"__type":...,"value":...}`）也当字面量处理。 */
internal object ParameterValueSerializer : KSerializer<ParameterValue> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.aharou.feature.plugin.workflow.ParameterValue")

    override fun deserialize(decoder: Decoder): ParameterValue {
        val input = decoder as? JsonDecoder
            ?: throw SerializationException("ParameterValue 只能从 JSON 反序列化")
        val element = input.decodeJsonElement()
        if (element is JsonPrimitive) {
            return ParameterValue.StaticValue(element.contentOrNull.orEmpty())
        }
        if (element !is JsonObject) return ParameterValue.Unsupported(element)
        val kind = simpleTypeName((element["__type"] as? JsonPrimitive)?.contentOrNull)
        return when (kind) {
            "NodeReference" -> ParameterValue.NodeReference(
                nodeId = (element["nodeId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            )
            "StaticValue" -> ParameterValue.StaticValue(
                value = (element["value"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            )
            else -> ParameterValue.Unsupported(element)
        }
    }

    override fun serialize(encoder: Encoder, value: ParameterValue) {
        val output = encoder as? JsonEncoder
            ?: throw SerializationException("ParameterValue 只能序列化成 JSON")
        val element: JsonElement = when (value) {
            is ParameterValue.StaticValue -> JsonObject(
                mapOf(
                    "__type" to JsonPrimitive(ParameterValue.StaticValue.serializer().descriptor.serialName),
                    "value" to JsonPrimitive(value.value),
                )
            )
            is ParameterValue.NodeReference -> JsonObject(
                mapOf(
                    "__type" to JsonPrimitive(ParameterValue.NodeReference.serializer().descriptor.serialName),
                    "nodeId" to JsonPrimitive(value.nodeId),
                )
            )
            is ParameterValue.Unsupported -> value.raw
        }
        output.encodeJsonElement(element)
    }
}

/** 模板里的数字可能写成 `"10800000"`、`3`、`"3.0"`，一律按宽容方式读成 Int。 */
internal object LenientIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        val input = decoder as? JsonDecoder ?: return decoder.decodeInt()
        return when (val element = input.decodeJsonElement()) {
            is JsonPrimitive -> element.contentOrNull?.trim()?.toDoubleOrNull()?.toInt() ?: 0
            else -> 0
        }
    }

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

internal object LenientFloatSerializer : KSerializer<Float> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientFloat", PrimitiveKind.FLOAT)

    override fun deserialize(decoder: Decoder): Float {
        val input = decoder as? JsonDecoder ?: return decoder.decodeFloat()
        return when (val element = input.decodeJsonElement()) {
            is JsonPrimitive -> element.contentOrNull?.trim()?.toFloatOrNull() ?: 0f
            else -> 0f
        }
    }

    override fun serialize(encoder: Encoder, value: Float) = encoder.encodeFloat(value)
}

internal object LenientBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val input = decoder as? JsonDecoder ?: return decoder.decodeBoolean()
        val element = input.decodeJsonElement()
        if (element !is JsonPrimitive) return false
        element.booleanOrNull?.let { return it }
        return when (element.contentOrNull?.trim()?.lowercase()) {
            "1", "yes", "y", "on", "true" -> true
            else -> false
        }
    }

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}

internal object LenientNullableStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientNullableString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        val input = decoder as? JsonDecoder ?: return decoder.decodeString()
        val element = input.decodeJsonElement()
        return (element as? JsonPrimitive)?.contentOrNull
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) {
            @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
            encoder.encodeNull()
        } else {
            encoder.encodeString(value)
        }
    }
}

/** `triggerConfig` 在模板里全是字符串，但宿主侧写入时可能给数字/布尔，统一字符串化。 */
internal object LenientStringMapSerializer : KSerializer<Map<String, String>> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("com.aharou.feature.plugin.workflow.LenientStringMap")

    override fun deserialize(decoder: Decoder): Map<String, String> {
        val input = decoder as? JsonDecoder ?: return emptyMap()
        val element = input.decodeJsonElement()
        if (element !is JsonObject) return emptyMap()
        return element.mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull.orEmpty() }
    }

    override fun serialize(encoder: Encoder, value: Map<String, String>) {
        val output = encoder as? JsonEncoder ?: return
        output.encodeJsonElement(JsonObject(value.mapValues { (_, v) -> JsonPrimitive(v) }))
    }
}

/** 便捷取字符串字段；缺失或非字符串返回 null。 */
internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

/** 便捷取字符串字段，缺失返回空串。 */
internal fun JsonObject.stringOrEmpty(key: String): String = stringOrNull(key).orEmpty()
