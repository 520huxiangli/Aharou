package com.aharou.feature.agent.domain.tool.question

import com.aharou.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AskUserQuestionToolTest {

    private lateinit var manager: AskUserQuestionManager
    private lateinit var tool: AskUserQuestionTool

    @Before
    fun setUp() {
        manager = AskUserQuestionManager()
        tool = AskUserQuestionTool(manager)
    }

    @Test
    fun execute_multiSelectWithCustomText_retainsBothSelectedAndCustom() = runTest {
        val args = mapOf(
            "questions" to buildJsonArray {
                add(
                    buildJsonObject {
                        put("question", "请选择需要包含的依赖？")
                        put("header", "依赖")
                        put("multiSelect", true)
                        put(
                            "options",
                            buildJsonArray {
                                add(buildJsonObject {
                                    put("label", "Ktor")
                                    put("description", "HTTP 客户端")
                                })
                                add(buildJsonObject {
                                    put("label", "Coroutines")
                                    put("description", "协程支持")
                                })
                            }
                        )
                    }
                )
            }
        )

        val deferredResult = async { tool.execute(args) }

        val pending = manager.pendingQuestion.filterNotNull().first()
        assertEquals(1, pending.questions.size)

        manager.resolve(
            pending.id,
            UserQuestionAnswer(
                listOf(
                    SingleAnswer(
                        question = "请选择需要包含的依赖？",
                        selected = listOf("Ktor", "Coroutines"),
                        customText = "Serialization"
                    )
                )
            )
        )

        val result = deferredResult.await()
        assertTrue(result is ToolResult.Success)
        val text = (result as ToolResult.Success).data.jsonPrimitive.content
        assertEquals("「请选择需要包含的依赖？」= Ktor、Coroutines、其他：Serialization", text)
    }

    @Test
    fun execute_singleSelectWithPresetOption() = runTest {
        val args = mapOf(
            "questions" to buildJsonArray {
                add(
                    buildJsonObject {
                        put("question", "选择方案？")
                        put("header", "方案")
                        put(
                            "options",
                            buildJsonArray {
                                add(buildJsonObject {
                                    put("label", "方案A")
                                    put("description", "选项A")
                                })
                                add(buildJsonObject {
                                    put("label", "方案B")
                                    put("description", "选项B")
                                })
                            }
                        )
                    }
                )
            }
        )

        val deferredResult = async { tool.execute(args) }
        val pending = manager.pendingQuestion.filterNotNull().first()

        manager.resolve(
            pending.id,
            UserQuestionAnswer(
                listOf(
                    SingleAnswer(
                        question = "选择方案？",
                        selected = listOf("方案A"),
                        customText = null
                    )
                )
            )
        )

        val result = deferredResult.await()
        assertTrue(result is ToolResult.Success)
        val text = (result as ToolResult.Success).data.jsonPrimitive.content
        assertEquals("「选择方案？」= 方案A", text)
    }

    @Test
    fun execute_singleSelectWithOnlyCustomText() = runTest {
        val args = mapOf(
            "questions" to buildJsonArray {
                add(
                    buildJsonObject {
                        put("question", "选择方案？")
                        put("header", "方案")
                        put(
                            "options",
                            buildJsonArray {
                                add(buildJsonObject {
                                    put("label", "方案A")
                                    put("description", "选项A")
                                })
                                add(buildJsonObject {
                                    put("label", "方案B")
                                    put("description", "选项B")
                                })
                            }
                        )
                    }
                )
            }
        )

        val deferredResult = async { tool.execute(args) }
        val pending = manager.pendingQuestion.filterNotNull().first()

        manager.resolve(
            pending.id,
            UserQuestionAnswer(
                listOf(
                    SingleAnswer(
                        question = "选择方案？",
                        selected = emptyList(),
                        customText = "自定义方案C"
                    )
                )
            )
        )

        val result = deferredResult.await()
        assertTrue(result is ToolResult.Success)
        val text = (result as ToolResult.Success).data.jsonPrimitive.content
        assertEquals("「选择方案？」= 其他：自定义方案C", text)
    }

    @Test
    fun execute_userSkipped_returnsSupplementNotice() = runTest {
        val args = mapOf(
            "questions" to buildJsonArray {
                add(
                    buildJsonObject {
                        put("question", "选择方案？")
                        put("header", "方案")
                        put(
                            "options",
                            buildJsonArray {
                                add(buildJsonObject {
                                    put("label", "方案A")
                                    put("description", "选项A")
                                })
                                add(buildJsonObject {
                                    put("label", "方案B")
                                    put("description", "选项B")
                                })
                            }
                        )
                    }
                )
            }
        )

        val deferredResult = async { tool.execute(args) }
        val pending = manager.pendingQuestion.filterNotNull().first()

        manager.resolve(pending.id, UserQuestionAnswer(emptyList()))

        val result = deferredResult.await()
        assertTrue(result is ToolResult.Success)
        val text = (result as ToolResult.Success).data.jsonPrimitive.content
        assertEquals("用户未在预设选项中做出选择，想补充说明。请根据用户后续补充的内容继续，或换一种方式提问。", text)
    }
}
