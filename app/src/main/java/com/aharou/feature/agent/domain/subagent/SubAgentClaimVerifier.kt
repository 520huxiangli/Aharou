package com.aharou.feature.agent.domain.subagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 子代理完成 claim 的解析与核验。
 *
 * 子代理在最终回复末尾附 ```json 块声明 `status` 与 `acceptance_criteria`。这里把 claim 与子会话里
 * **真实发生过**的工具调用对照：核验不上的条目标为未达成，自报 `complete` 相应降级，避免虚报。
 * 命中不了的 claim 一律按未达成处理——「声称完成」不构成完成。
 */
object SubAgentClaimVerifier {

    /** 子代理自报的完成声明。 */
    data class Claim(
        val status: String,
        val summary: String,
        val criteria: List<Criterion>
    )

    /** 单条验收项。`type` 为 verification / files / manual。 */
    data class Criterion(
        val type: String,
        val claim: String,
        val command: String? = null,
        val paths: List<String> = emptyList()
    )

    /** 核验证据：子会话里真实执行成功的命令原文与真实写入成功的文件路径。 */
    data class Evidence(
        val succeededCommands: Set<String>,
        val writtenPaths: Set<String>
    )

    /** 单条核验结果。 */
    data class VerifiedItem(
        val claim: String,
        val type: String,
        val passed: Boolean,
        val note: String
    )

    /** 核验报告。`effectiveStatus` 才是应按之处理的结论。 */
    data class Report(
        val declaredStatus: String,
        val effectiveStatus: String,
        val summary: String,
        val downgraded: Boolean,
        val items: List<VerifiedItem>
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 只认末尾的 json 围栏；`{` `}` 都转义，避开 Android ICU 对裸花括号的解析问题。 */
    private val fence = Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```", RegexOption.IGNORE_CASE)

    private val VALID_STATUS = setOf("complete", "partial", "failed")
    private val VALID_TYPE = setOf("verification", "files", "manual")

    /** 解析 claim 块；没有声明或结构不合法返回 null（不当作虚报，只是未声明）。 */
    fun parse(finalText: String): Claim? {
        val block = fence.findAll(finalText).lastOrNull()?.groupValues?.get(1) ?: return null
        val obj = runCatching { json.parseToJsonElement(block).jsonObject }.getOrNull() ?: return null
        val status = obj["status"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: return null
        if (status !in VALID_STATUS) return null

        return Claim(
            status = status,
            summary = obj["summary"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            criteria = obj["acceptance_criteria"]?.let { el ->
                runCatching { el.jsonArray }.getOrNull().orEmpty().mapNotNull { item ->
                    val c = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val type = c["type"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: return@mapNotNull null
                    if (type !in VALID_TYPE) return@mapNotNull null
                    Criterion(
                        type = type,
                        claim = c["claim"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                        command = c["command"]?.jsonPrimitive?.contentOrNull?.trim(),
                        paths = c["paths"]?.let { p ->
                            runCatching { p.jsonArray }.getOrNull().orEmpty()
                                .mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
                        }.orEmpty()
                    )
                }
            }.orEmpty()
        )
    }

    /** 逐条核验；`manual` 条目（只有用户能验的事）不判定通过与否，也不影响状态。 */
    fun verify(claim: Claim, evidence: Evidence): Report {
        val items = claim.criteria.map { c ->
            when (c.type) {
                "verification" -> {
                    val cmd = c.command?.trim()
                    when {
                        cmd.isNullOrBlank() ->
                            VerifiedItem(c.claim, c.type, false, "未附 command，无法核验")
                        evidence.succeededCommands.any { it.trim() == cmd } ->
                            VerifiedItem(c.claim, c.type, true, "命令已核实执行成功")
                        else ->
                            VerifiedItem(c.claim, c.type, false, "本任务里没有该命令的成功执行记录")
                    }
                }
                "files" -> {
                    val missing = c.paths.filter { p -> evidence.writtenPaths.none { it.trim() == p } }
                    when {
                        c.paths.isEmpty() ->
                            VerifiedItem(c.claim, c.type, false, "未附 paths，无法核验")
                        missing.isEmpty() ->
                            VerifiedItem(c.claim, c.type, true, "文件已核实写入成功")
                        else ->
                            VerifiedItem(c.claim, c.type, false, "以下路径没有成功写入记录：${missing.joinToString("、")}")
                    }
                }
                else -> VerifiedItem(c.claim, c.type, true, "需用户确认，未自动核验")
            }
        }
        val failed = items.count { !it.passed && it.type != "manual" }
        val effective = if (failed > 0 && claim.status == "complete") "partial" else claim.status
        return Report(
            declaredStatus = claim.status,
            effectiveStatus = effective,
            summary = claim.summary,
            downgraded = effective != claim.status,
            items = items
        )
    }

    /** 剔除末尾的 claim 协议块后的结论文本——原始 JSON 不进父上下文。 */
    fun stripClaimBlock(finalText: String): String {
        val match = fence.findAll(finalText).lastOrNull() ?: return finalText
        return finalText.removeRange(match.range).trim()
    }

    /** 父汇总里的裁定段：终态、逐条核验与说明。 */
    fun renderAdjudication(report: Report): String = buildString {
        append(if (report.downgraded) "按 ${report.effectiveStatus} 处理（自报 ${report.declaredStatus}，核验后降级）" else "按 ${report.effectiveStatus} 处理")
        append("：")
        report.items.forEach { item ->
            append("\n- [")
            append(if (item.passed) "x" else " ")
            append("] ${item.claim}（${item.type}）：${item.note}")
        }
    }
}
