package com.aharou.feature.agent.domain.workflow

/**
 * 从 [StatefulAgentWorkflow] 抽出的自包含逻辑：不吃工作流状态、纯输入到输出。
 * 抽出来是为了给那个文件腾出行数预算，行为与原来逐字一致。
 */

/** 采样循环检测的候选单元长度：先是短单元（同一句话反复），再是长单元（整段思考重来）。 */
private val LOOP_UNIT_CANDIDATES = (2..16) + listOf(32, 64, 128, 256, 512)

/** 长单元判为循环所需的「重复总量」下限（字符）：正常输出不会把上千字符原文重来一遍。 */
private const val LOOP_MIN_REPEATED_CHARS = 1024

/**
 * 采样循环检测：模型偶尔会连续吐出同一片段（思考里尤其常见，一句话重复几十遍）。
 * 命中时返回重复起点的下标，调用方截断到该处只保留第一次；未命中返回 -1。
 * 单元长度下限 2、连续重复下限 20 次，避免把 `---` 分隔符或少量重复误判成循环。
 */
internal fun samplingLoopStart(text: CharSequence, minRepeat: Int = 20, maxUnit: Int = 16): Int {
    val len = text.length
    for (unit in LOOP_UNIT_CANDIDATES) {
        // 短单元沿用「连续重复 ≥ minRepeat 次」；长单元改用「重复总量」定门槛——上下文过长时
        // 模型会把整段思考重来（单元几十上百字符），只查到 maxUnit 就永远判不出来，界面上表现
        // 为「一直在思考」。长单元要重复到相当体量才算循环，免得误伤正常的结构化输出。
        val repeats = if (unit <= maxUnit) minRepeat else maxOf(3, LOOP_MIN_REPEATED_CHARS / unit)
        val need = unit * repeats
        if (len < need) continue
        val end = len
        // 单元内全是同一个字符时不算重复：`--------`、`====` 这类分隔线和表格线
        // 满足任意 unit 的周期条件，不排除会被误判成循环。
        if ((1 until unit).none { text[end - unit] != text[end - unit + it] }) continue
        var repeated = true
        var i = 1
        while (repeated && i < repeats) {
            var j = 0
            while (j < unit) {
                if (text[end - unit + j] != text[end - unit * (i + 1) + j]) {
                    repeated = false
                    break
                }
                j++
            }
            i++
        }
        if (repeated) {
            // 尾部窗口只够证明「重复了 repeats 次」，前面往往还连着更多次；
            // 向前回溯到周期真正的起点，只留第一次。
            var start = end - unit
            while (start - unit >= 0) {
                var same = true
                for (k in 0 until unit) {
                    if (text[start - unit + k] != text[start + k]) {
                        same = false
                        break
                    }
                }
                if (!same) break
                start -= unit
            }
            return start + unit
        }
    }
    return -1
}

/**
 * 采样循环命中后的收尾：把累积文本截到首次出现处，并向 UI 报一条命中事件。
 * 放在这里而不是 [StatefulAgentWorkflow] 里，是为了不动那个文件的行数预算。
 */
internal suspend fun cutSamplingLoop(
    text: StringBuilder,
    loopStart: Int,
    inReasoning: Boolean,
    emit: suspend (AgentEvent) -> Unit,
) {
    text.setLength(loopStart)
    emit(AgentEvent.SamplingLoopDetected(inReasoning = inReasoning))
}
