package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolParameter

/**
 * `task` 工具的**参数表**（纯数据，无逻辑）。
 *
 * 抽成独立文件的唯一理由是行数棘轮：[TaskTool] 已顶在架构门禁上限、只许降不许升，
 * 而参数表占了一百多行、又常随动作增删调整。搬出来后，改参数说明不再需要挤占主分发路径的行数预算。
 */
internal object TaskToolSpec {

    val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作类型：create（默认，创建子代理并执行任务）/ send（向子代理发一条消息，可反复发送）/ read（读取子代理的最后输出）/ stop（停止子代理的执行）/ del（删除子代理会话及其消息）/ list（列出当前会话的全部子代理）/ roles（列出可用子代理角色，可按 department、agentQuery 过滤排序，用于查名字后再用 agent 点名）/ team（列出同批队友：同一父会话下的其它子代理及其任务与状态）/ tell（向同批队友发一条消息，配 id 与 message 使用）/ grant（运行期给已派发的子代理追加可写路径：id + writePaths）/ teams（列出团队预设 teams/*.md；给 team 参数看单个预设的阶段与成员）/ team_run（按团队预设跑完整流程：team + goal，阶段内并行、阶段间串行）",
            required = false
        ),
        "id" to ToolParameter(
            name = "id",
            type = ParameterType.STRING,
            description = "子会话 id（read/send/stop/del/tell 必填，取自 task 返回的 id 或 team 名单）",
            required = false
        ),
        "description" to ToolParameter(
            name = "description",
            type = ParameterType.STRING,
            description = "子代理任务描述（create 用，作为子会话标题，如「修复登录 bug」）",
            required = false
        ),
        "prompt" to ToolParameter(
            name = "prompt",
            type = ParameterType.STRING,
            description = "给子代理的完整指令（create 必填），将作为它的第一条用户消息；子代理看到的是全新上下文",
            required = false
        ),
        "agent" to ToolParameter(
            name = "agent",
            type = ParameterType.STRING,
            description = "自定义子代理名（create 可选）：仅在确知系统提示词「可用子代理」索引里的确切名称时使用，一般改用 department + agentQuery 让系统选角。省略则用继承本会话模型的默认通用子代理",
            required = false
        ),
        "department" to ToolParameter(
            name = "department",
            type = ParameterType.STRING,
            description = "子代理部门（create 可选）：取系统提示词「可用子代理」部门索引里的部门名，如 engineering / security / testing / design / product / project-management / specialized / game-development / unity / unreal / godot / roblox / xr / explore。与 agentQuery 配合在该部门内选角",
            required = false
        ),
        "agentQuery" to ToolParameter(
            name = "agentQuery",
            type = ParameterType.STRING,
            description = "选角关键词（create 可选，2~5 个词）：描述任务的功能或领域，如 \"code review\"、\"database migration\"、\"ui design\"。与 department 配合在该部门内按名称与用途描述打分选一个角色，比记角色名更可靠",
            required = false
        ),
        "model" to ToolParameter(
            name = "model",
            type = ParameterType.STRING,
            description = "子代理使用的模型名（create 可选）：显式传给子会话，覆盖角色定义里的 model；留空则继承角色定义，未选角色时继承本会话模型",
            required = false
        ),
        "provider" to ToolParameter(
            name = "provider",
            type = ParameterType.STRING,
            description = "子代理使用的提供商（create 可选）：可写 providerId 或设置里显示的提供商名称，覆盖角色定义里的 provider；留空则继承角色定义，未选角色时继承本会话 provider",
            required = false
        ),
        "writePaths" to ToolParameter(
            name = "writePaths",
            type = ParameterType.ARRAY,
            description = "该子代理允许写入的文件路径（create 可选）。纯调研/分析传 [] 表示只读（写文件类工具会被直接拒绝）；需要落盘时传精确文件或目录；确需独占整个工作区才传 [\"*\"]。省略表示不限制。注意：闸门只约束 writeFile/editFile，shell 重定向不受限。",
            required = false,
            itemsSchema = mapOf("type" to "string")
        ),
        "pipeline" to ToolParameter(
            name = "pipeline",
            type = ParameterType.ARRAY,
            description = "流水线任务列表（action=pipeline 必填）。每项：{id, description, prompt, dependsOn?, writePaths?, agent? / department?+agentQuery?, model?, provider?}。" +
                "无依赖的任务同层并发跑；有依赖的等上游全部完成后才跑，且上游产出会自动接在它的 prompt 前面。" +
                "同一层任务的 writePaths 不得相交（含目录与其中文件的关系）。",
            required = false,
            itemsSchema = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "id" to mapOf("type" to "string", "description" to "本流水线内唯一的任务 id"),
                    "description" to mapOf("type" to "string", "description" to "短标题"),
                    "prompt" to mapOf("type" to "string", "description" to "完整任务指令"),
                    "dependsOn" to mapOf(
                        "type" to "array",
                        "items" to mapOf("type" to "string"),
                        "description" to "依赖的任务 id 列表"
                    ),
                    "writePaths" to mapOf(
                        "type" to "array",
                        "items" to mapOf("type" to "string"),
                        "description" to "允许写入的路径；[] 表示只读，省略表示不限制"
                    ),
                    "agent" to mapOf("type" to "string"),
                    "department" to mapOf("type" to "string"),
                    "agentQuery" to mapOf("type" to "string"),
                    "model" to mapOf("type" to "string", "description" to "该分支使用的模型；留空继承角色定义或父会话"),
                    "provider" to mapOf("type" to "string", "description" to "该分支使用的提供商（providerId 或名称）；留空继承角色定义或父会话")
                )
            )
        ),
        "team" to ToolParameter(
            name = "team",
            type = ParameterType.STRING,
            description = "团队预设名（teams / team_run 用）：取自 task(action=\"teams\") 列出的名字；teams 给了就展开该预设的阶段与成员，team_run 必须给",
            required = false
        ),
        "goal" to ToolParameter(
            name = "goal",
            type = ParameterType.STRING,
            description = "本次团队要达成的目标（team_run 必填）：会写进每个成员的任务指令，成员按阶段分工把它做完",
            required = false
        ),
        "message" to ToolParameter(
            name = "message",
            type = ParameterType.STRING,
            description = "发给子代理的消息正文（send/tell 必填）。可反复调用；运行中的子代理会尽快收到，已完成的会被重新唤醒",
            required = false
        )
    )
}
