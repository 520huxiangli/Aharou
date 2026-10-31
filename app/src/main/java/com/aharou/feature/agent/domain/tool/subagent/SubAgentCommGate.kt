package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.tool.ToolResult

/**
 * 子代理通信总开关在**工具文案与拒绝**上的唯一出口。
 *
 * 为什么单独一个文件：`TaskTool.kt` 正卡在架构门禁的行数棘轮上（只许降不许升），
 * 而「关掉时怎么拒绝」「描述里怎么说」这两段既不属于派发逻辑、又会随产品文案反复调整，
 * 放在这里既不撑大 TaskTool，也免得改动工具说明时还要碰主分发路径。
 *
 * 开关只管「同批对等通信」（`team` / `tell`）；父代理对子代理的 `send` / `read` / `stop`
 * 是上下级通道，不受影响。
 */
internal object SubAgentCommGate {

    /** 关闭时 `team` / `tell` 的统一拒绝：说清「现在关着」「去哪开」「还有哪条路可用」，免得模型反复重试。 */
    fun disabledError(): ToolResult = ToolResult.Error(
        "子代理之间的通信已在设置里关闭（设置 → 子代理 → 允许子代理互相对话）。" +
            "子代理请把要转达的内容写进最终回答；父代理仍可用 send / read 与子代理收发。",
        "COMMUNICATION_DISABLED"
    )

    /**
     * 工具描述里那句「子代理之间能不能通气」。
     *
     * 工具说明是模型唯一的动作索引：开关改了说明却没改，模型会照着旧说明白试一轮 `team` / `tell`。
     */
    fun descriptionHint(enabled: Boolean): String =
        if (enabled) {
            "分支上的子代理可用 team 查队友、tell 互相发消息；"
        } else {
            "子代理之间的互通已被主人关闭，不要调 team / tell；"
        }

    /**
     * 新子代理简报里的协作指引（[teamBriefing] 用）。
     *
     * 与 [descriptionHint] 分开：那句是给主代理看的工具说明，这句是写给子代理自己看的行为约定，
     * 关闭时对子代理该说的是「把结论写进最终回答」，而不是「别调工具」。
     */
    fun briefingLine(enabled: Boolean): String =
        if (enabled) {
            "查看实时队友：task(action=\"team\")；给队友发消息：task(action=\"tell\", id=\"<队友 id>\", message=\"…\")。"
        } else {
            "子代理之间的对话已被主人关闭：不要调 team / tell，需要转达的信息请写进最终结论。"
        }
}
