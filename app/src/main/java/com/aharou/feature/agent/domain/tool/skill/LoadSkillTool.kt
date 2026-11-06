package com.aharou.feature.agent.domain.tool.skill

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.skill.SkillFileListing
import com.aharou.feature.agent.domain.skill.SkillRepository
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 让 AI 按需加载一个技能的完整指令正文。
 *
 * 系统提示里只注入了各 skill 的 name+description 清单；AI 判断某个 skill 适用时，调用本工具拿到
 * SKILL.md 正文（可能含「先 apk add python3，再 python ~/.aharou/skills/<name>/x.py」之类的执行步骤），
 * 随后用 read_file/execute_command 等工具按正文行事。skill 目录在当前执行环境为 `~/.aharou/skills/<name>/`。
 */
class LoadSkillTool @Inject constructor(
    private val skillRepository: SkillRepository
) : AgentTool() {
    private companion object {
        const val TAG = "LoadSkillTool"
    }

    override val name = "loadSkill"
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG)
    override val description =
        "加载指定技能的完整指令正文。当系统提示「可用技能」清单中的技能适用于当前任务时使用。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "skill_name" to ToolParameter(
            name = "skill_name",
            type = ParameterType.STRING,
            description = "要加载的技能名称（与系统提示「可用技能」清单中的名称一致）。",
            required = true
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val skillName = args["skill_name"]?.jsonPrimitive?.contentOrNull?.trim()
        if (skillName.isNullOrEmpty()) {
            return ToolResult.Error("缺少必需参数: skill_name", "MISSING_SKILL_NAME")
        }

        val instructions = skillRepository.loadInstructions(skillName)
        if (instructions == null) {
            val available = skillRepository.listSkills().joinToString(", ") { it.name }
            FileLogger.w(TAG, "load_skill 未找到: $skillName，可用: $available")
            return ToolResult.Error(
                "未找到技能「$skillName」。可用技能: ${available.ifEmpty { "（无）" }}",
                "SKILL_NOT_FOUND"
            )
        }

        val files = skillRepository.listSkillFiles(skillName)
        FileLogger.d(
            TAG,
            "load_skill 加载成功: $skillName (${instructions.length} 字符, ${files.files.size} 个附属文件)"
        )
        return ToolResult.Success(JsonPrimitive(withSkillFiles(instructions, files)))
    }

    /**
     * 正文原样保留（模型靠它干活，一个字都不能截），只在末尾补上技能根目录与附属文件清单。
     *
     * 技能正文常按 `~/.aharou/skills/<name>/x.py` 引用同目录脚本；直接给出根目录和清单，
     * 模型就不必先自己 `list` 探一轮，也不会把 node_modules 这类目录整片列进上下文。
     */
    private fun withSkillFiles(instructions: String, files: SkillFileListing): String {
        val root = files.root ?: return instructions
        val text = StringBuilder(instructions)
        text.append("\n\nSkill root: ").append(root)
        text.append("\n清单里是相对路径，用的时候拼在 Skill root 后面。")
        if (files.files.isNotEmpty()) {
            text.append("\nAvailable skill files:")
            files.files.forEach { text.append("\n- ").append(it) }
        }
        if (files.truncated) {
            text.append("\n（文件过多，以上只列出前 ${files.files.size} 个；其余文件用 list 工具查看 Skill root）")
        }
        return text.toString()
    }
}
