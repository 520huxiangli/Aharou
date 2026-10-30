package com.aharou.feature.agent.domain.tool.question

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 提问内容 / 答案与 JSON 文本的互转（落库用）。
 *
 * 解析一律宽松：字段缺失按默认值、坏条目跳过；整份读不懂时返回 null，由调用方判失效。
 * 手写编解码而不是给领域模型加 `@Serializable`，是为了让旧版本写进去的行在新版本里仍能读出来
 * 而不必担心字段顺序与默认值变化。
 */
internal object QuestionJsonCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun encodeQuestions(questions: List<QuestionItem>): String = buildJsonArray {
        questions.forEach { question ->
            addJsonObject {
                put("question", question.question)
                put("header", question.header)
                put("multiSelect", question.multiSelect)
                putJsonArray("options") {
                    question.options.forEach { option ->
                        addJsonObject {
                            put("label", option.label)
                            put("description", option.description)
                        }
                    }
                }
            }
        }
    }.toString()

    /** 解析失败或没有任何有效问题时返回 null（问题不可重建，只能判失效）。 */
    fun decodeQuestions(raw: String): List<QuestionItem>? = runCatching {
        json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
            val obj = element.jsonObject
            val question = obj["question"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (question.isEmpty()) return@mapNotNull null
            val options = obj["options"]?.jsonArray.orEmpty().mapNotNull { optionElement ->
                val optionObj = optionElement.jsonObject
                val label = optionObj["label"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (label.isEmpty()) return@mapNotNull null
                QuestionOption(
                    label = label,
                    description = optionObj["description"]?.jsonPrimitive?.content.orEmpty()
                )
            }
            QuestionItem(
                question = question,
                header = obj["header"]?.jsonPrimitive?.content.orEmpty(),
                options = options,
                multiSelect = obj["multiSelect"]?.jsonPrimitive?.content?.toBoolean() ?: false
            )
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun encodeAnswer(answer: UserQuestionAnswer): String = buildJsonArray {
        answer.answers.forEach { single ->
            addJsonObject {
                put("question", single.question)
                putJsonArray("selected") { single.selected.forEach { add(it) } }
                single.customText?.let { put("customText", it) }
            }
        }
    }.toString()

    fun decodeAnswer(raw: String): UserQuestionAnswer? = runCatching {
        UserQuestionAnswer(
            json.parseToJsonElement(raw).jsonArray.map { element ->
                val obj = element.jsonObject
                SingleAnswer(
                    question = obj["question"]?.jsonPrimitive?.content.orEmpty(),
                    selected = obj["selected"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                    customText = obj["customText"]?.jsonPrimitive?.content
                )
            }
        )
    }.getOrNull()
}
