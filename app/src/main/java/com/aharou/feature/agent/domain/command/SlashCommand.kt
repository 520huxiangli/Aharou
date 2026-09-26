package com.aharou.feature.agent.domain.command

import androidx.annotation.StringRes
import com.aharou.feature.agent.domain.skill.Skill

/** 提示型命令正文里的参数占位符。 */
internal const val ARGUMENTS_PLACEHOLDER = "\$ARGUMENTS"

/**
 * 把模板里的 `$ARGUMENTS` 替换为 [args]；模板没有占位符且 [args] 非空时，
 * 追加一行，避免用户输入丢失。
 */
internal fun substituteArguments(template: String, args: String): String = when {
    template.contains(ARGUMENTS_PLACEHOLDER) -> template.replace(ARGUMENTS_PLACEHOLDER, args)
    args.isBlank() -> template
    else -> "$template\n\n用户附加输入：$args"
}

/** 斜杠命令来源：内置命令（本地动作）或技能（把 SKILL.md 正文注入本轮）。 */
enum class SlashCommandKind { ACTION, SKILL }

/**
 * 输入框 `/` 菜单的展示模型，内置命令与技能统一表示。
 *
 * @param name 命令名，不含前导 `/`（如 `status`、`pdf-report`）。
 * @param description 已解析的菜单描述（内置命令取字符串资源，技能取其 `description`）。
 * @param kind 来源类型，供菜单区分展示。
 * @param acceptsArgs 是否接受参数；为 true 时菜单点击会补一个空格，便于继续输入参数。
 */
data class SlashCommand(
    val name: String,
    val description: String,
    val kind: SlashCommandKind,
    val acceptsArgs: Boolean = false
)

/**
 * 内置斜杠命令处理器（本地动作型）。每条命令实现一个此类，
 * 通过 Hilt `@Binds @IntoSet` 汇集到 [SlashCommandRegistry]。
 *
 * 新增命令只需：新建实现类 + 在 [SlashCommandContext] 补对应方法 + ViewModel 实现 +
 * 在 [SlashCommandModule] 追加一行绑定。
 */
interface ActionCommandHandler {

    /** 命令名，不含前导 `/`。 */
    val name: String

    /** 菜单描述，来自双语字符串资源。 */
    @get:StringRes
    val descriptionRes: Int

    /** 是否接受参数。默认不接受：带多余参数时不命中（回落为普通消息，不吞用户输入）。 */
    val acceptsArgs: Boolean get() = false

    /** 命中后执行。[args] 为命令名之后的原始文本（已 trim）。 */
    fun execute(context: SlashCommandContext, args: String)
}

/**
 * 命令执行上下文：只暴露命令需要的最小能力，避免命令直接持有 ViewModel。
 * 由 AIAgentViewModel 实现，新增命令时在此接口补方法并在 ViewModel 实现。
 */
interface SlashCommandContext {
    fun compactCurrentSession()

    /** /usage —— 以 Markdown 表格输出今日与累计的调用次数、token 用量与预估费用。 */
    fun showUsage()

    /** /init —— 触发一次 agent 回合，分析代码库并生成/改进项目规则文件；[prompt] 为指令正文。 */
    fun initProject(prompt: String)

    /** 技能触发：把 [skill] 正文作为本轮指令执行，[args] 为附加参数。 */
    fun runSkill(skill: Skill, args: String)
}
