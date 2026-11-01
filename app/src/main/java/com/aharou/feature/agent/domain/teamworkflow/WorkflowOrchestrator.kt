package com.aharou.feature.agent.domain.teamworkflow

/**
 * 工作流分层的判定协议与驳回台账。
 *
 * 判定由每层的协调者（[WorkflowTeamRef.lead]，留空则由该团队首个任务的角色兼任）在子代理会话里
 * 用一行固定标记给出，主代理只做解析与调度，不替协调者做判断：
 * ```
 * [[JUDGE]] HANDLE                    # 本层职责内，已处理完，不再往下流转
 * [[JUDGE]] HANDOFF                   # 需要下一层接续
 * [[JUDGE]] REJECT | 需求分析团队·需求分析师 | 接口契约 | 缺分页约定
 * ```
 * 驳回是专家对专家、交付对交付：只退那一份交付，不推翻整条链。
 *
 * 这一层刻意只做纯文本与状态，不碰 JSON、不派子代理：协议的解析与轮次上限是整条链最容易出错的
 * 地方，放在单测里覆盖比在真机上试错便宜得多。
 */
object WorkflowOrchestrator {

    /** 判定标记。协调者的输出里只看带这个标记的最后一行，其余正文不进解析。 */
    const val JUDGE_MARKER = "[[JUDGE]]"

    /** 判定给不出时的兜底动作取 HANDOFF：中断整条链比多流转一层更容易让用户的请求落空。 */
    sealed interface Verdict {
        /** 本层处理完毕，不再往下。 */
        data object Handle : Verdict

        /** 转交下一层，本层产出作为下游输入。 */
        data object Handoff : Verdict

        /**
         * 驳回上游某份交付。[targetTeam]/[targetExpert] 可为空：协调者只写了专家名时，
         * 默认退回上一层同名专家；两者都空则退回上一层协调者。
         */
        data class Reject(
            val targetTeam: String?,
            val targetExpert: String?,
            val deliverable: String,
            val reason: String
        ) : Verdict

        /** 协调者没按协议输出（或输出为空）；[raw] 只用于在报告里指出来。 */
        data class Unknown(val raw: String) : Verdict
    }

    /** 驳回往返的记账结果：[allowed] 为 false 表示这一对专家已经退到上限，该升级裁决了。 */
    data class Round(val used: Int, val allowed: Boolean)

    /**
     * 同一对专家的往返计数。上限按 [TeamWorkflow.maxRejectRounds] 走，到顶就不再放行——
     * 上下游互不服气时，无限往返会把 token 烧光。
     */
    class RejectLedger(private val maxRounds: Int) {
        private val used = mutableMapOf<String, Int>()

        fun register(key: String): Round {
            val next = (used[key] ?: 0) + 1
            used[key] = next
            return Round(used = next, allowed = next <= maxRounds)
        }

        /** 同一对专家已经用掉的轮次，供报告展示。 */
        fun usedOf(key: String): Int = used[key] ?: 0
    }

    /** 驳回记账键：上游层与下游层、双方专家名共同确定一对人。 */
    fun rejectKey(upstreamLayer: Int, upstreamExpert: String, downstreamLayer: Int, downstreamExpert: String): String =
        "$upstreamLayer:$upstreamExpert→$downstreamLayer:$downstreamExpert"

    fun parseVerdict(output: String): Verdict {
        val line = output.lineSequence()
            .map { it.trim() }
            .filter { it.contains(JUDGE_MARKER, ignoreCase = true) }
            .lastOrNull()
            ?: return Verdict.Unknown(output.takeLast(RAW_KEEP).trim())

        // substringAfter 严格区分大小写，模型常把标记写成小写，必须先按忽略大小写定好位置。
        val markerAt = line.indexOf(JUDGE_MARKER, ignoreCase = true)
        val body = line.substring(markerAt + JUDGE_MARKER.length).trim().trimStart(':', '：', '-').trim()
        if (body.isEmpty()) return Verdict.Unknown(line.take(RAW_KEEP))
        // 判定动词后面可能直接跟分隔符（`HANDLE：`）或紧跟 `|`（`REJECT | …`），先按两种分隔符切头。
        val head = body.substringBefore('|').substringBefore('：').trim().uppercase()
        return when {
            head.startsWith("HANDLE") -> Verdict.Handle
            head.startsWith("HANDOFF") || head.startsWith("HAND OFF") -> Verdict.Handoff
            head.startsWith("REJECT") -> parseReject(body)
            else -> Verdict.Unknown(body.take(RAW_KEEP))
        }
    }

    /** `REJECT | 团队·专家 | 交付 | 理由`；理由里允许再出现 `|`，所以只用前两段按位置切。 */
    private fun parseReject(body: String): Verdict {
        // 同 parseVerdict：动词可能是小写，按忽略大小写切。
        val at = body.indexOf("REJECT", ignoreCase = true)
        val rest = body.substring(at + "REJECT".length).trim()
            .trimStart(':', '：', '|', '-', ' ')
            .trim()
        if (rest.isEmpty()) return Verdict.Reject(null, null, "", "上游交付未通过本层校验（未附具体说明）")
        val parts = rest.split('|').map { it.trim() }
        val target = parts.getOrNull(0).orEmpty()
        val deliverable = parts.getOrNull(1).orEmpty()
        val reason = parts.drop(2).joinToString(" | ").trim()
        val (team, expert) = splitTarget(target)
        return Verdict.Reject(
            targetTeam = team,
            targetExpert = expert,
            deliverable = deliverable.ifEmpty { "上游交付" },
            reason = reason.ifEmpty { "未附具体理由，请按本层结论补齐" }
        )
    }

    /** `团队·专家`、`团队/专家`、`专家` 三种写法都认；空串返回 (null, null)。 */
    private fun splitTarget(target: String): Pair<String?, String?> {
        val text = target.trim()
        if (text.isEmpty()) return null to null
        val separator = listOf('·', '/', '／', '@').firstOrNull { text.contains(it) } ?: return null to text
        val team = text.substringBefore(separator).trim().ifEmpty { null }
        val expert = text.substringAfter(separator).trim().ifEmpty { null }
        return team to expert
    }

    /**
     * 协调者任务指令：读完本层与上游事实后，只输出一行判定。
     *
     * 判定要求写在最前面而不是结尾——子代理的收尾常被自己的实现细节带走，先立规矩更容易守住。
     */
    fun judgePrompt(
        workflow: TeamWorkflow,
        layerIndex: Int,
        teamName: String,
        goal: String,
        hasNextLayer: Boolean
    ): String = buildString {
        append("你是工作流「").append(workflow.name).append("」第 ").append(layerIndex).append(" 层团队「")
        append(teamName).append("」的协调者。\n")
        append("本次用户请求：").append(goal).append("\n")
        if (workflow.charter.isNotBlank()) append("\n工作流公约：\n").append(workflow.charter).append("\n")
        append("\n先判断这项工作是否属于本层职责，再决定怎么收尾。你的回答最后必须单独占一行写出判定，")
        append("解析器只认这一行：\n")
        append("  ").append(JUDGE_MARKER).append(" HANDLE    # 本层职责内、已处理完，不需要下一层\n")
        if (hasNextLayer) {
            append("  ").append(JUDGE_MARKER).append(" HANDOFF   # 需要下一层接续（说明交给下一层做什么）\n")
            // 第一层没有上游，驳回无从谈起；acceptsReject 说的是「这一层可以退上一层」。
            if (layerIndex > 1 && workflow.teams[layerIndex - 1].acceptsReject) {
                append("  ").append(JUDGE_MARKER)
                append(" REJECT | 上游团队·上游专家 | 哪一份交付 | 为什么不接受\n")
                append("# REJECT 只能退上一层的某一份交付，不要整层打回；退不掉的问题用 HANDLE 说明并自己收尾。\n")
            }
        } else {
            append("# 这是最后一层，没有下一层可转交：要么 HANDLE，要么驳回上游。\n")
        }
        append("判定之外的实现过程照常做，不要把判定混进正文段落里。")
    }

    /**
     * 返工任务指令：让被点名的那位上游专家在自己的会话里重做那一份交付。
     *
     * 明确要求「只改被退回的那一份」：驳回是交付对交付，放任它顺手重构会把上一层的其他结论一起推翻。
     */
    fun reworkPrompt(
        workflow: TeamWorkflow,
        targetLayer: Int,
        targetTeam: String,
        targetExpert: String,
        reject: Verdict.Reject,
        goal: String,
        round: Int
    ): String = buildString {
        append("你是工作流「").append(workflow.name).append("」第 ").append(targetLayer).append(" 层团队「")
        append(targetTeam).append("」的 ").append(targetExpert).append("。\n")
        append("本次用户请求：").append(goal).append("\n\n")
        append("下游第 ").append(targetLayer + 1).append(" 层驳回你提交的交付「").append(reject.deliverable)
        append("」，理由：").append(reject.reason).append("\n")
        append("这是同一处交付的第 ").append(round).append(" 轮返工。请在你的会话里重做这一份交付，")
        append("只改被退回的部分，不要顺手改动上一层的其他结论；完成后给出一段可以直接被下游消费的最终交付，")
        append("并在结尾写明改了什么。")
    }

    /** 到顶不再放行时的升级说明：交给主代理裁决，而不是让两层继续互退。 */
    fun escalationMessage(
        workflow: TeamWorkflow,
        reject: Verdict.Reject,
        downstreamTeam: String,
        downstreamExpert: String,
        upstreamTeam: String,
        upstreamExpert: String,
        rounds: Int
    ): String = "工作流「${workflow.name}」在「$downstreamTeam·$downstreamExpert」与「$upstreamTeam·$upstreamExpert」" +
        "之间就「${reject.deliverable}」往返已达 $rounds 轮上限（maxRejectRounds=${workflow.maxRejectRounds}）：" +
        "最新驳回理由为「${reject.reason}」。已停止自动往返，交由主代理裁决——" +
        "可由主代理自己改这一份交付，或调整工作流的团队链后重跑。"

    /** 一层跑完后的报告行，出问题时要能一眼看出是哪层、判定是什么。 */
    fun reportLine(layerIndex: Int, teamName: String, verdict: Verdict): String = when (verdict) {
        is Verdict.Handle -> "第 $layerIndex 层「$teamName」：HANDLE，本层处理完毕，不再往下流转"
        is Verdict.Handoff -> "第 $layerIndex 层「$teamName」：HANDOFF，转交下一层"
        is Verdict.Reject -> "第 $layerIndex 层「$teamName」：REJECT → " +
            "${verdict.targetTeam ?: "上一层"}·${verdict.targetExpert ?: "协调者"} 的「${verdict.deliverable}」"
        is Verdict.Unknown -> "第 $layerIndex 层「$teamName」：未按协议给出判定（按 HANDOFF 继续），原文片段：" +
            verdict.raw.ifEmpty { "空" }
    }

    private const val RAW_KEEP = 200
}
