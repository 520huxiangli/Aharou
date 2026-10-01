package com.aharou.feature.agent.domain.skill.market

import kotlinx.serialization.Serializable

/**
 * 安装前的风险提示。
 *
 * 只依据「看得见的东西」判断：技能包里的文件名 + SKILL.md 正文。不下载整包、不执行、不联网，
 * 所以它报的是「有什么」而不是「危不危险」——判断留给用户。
 */
@Serializable
data class SkillSafety(
    /** 技能包里附带的可执行脚本，路径相对技能目录。 */
    val scripts: List<String> = emptyList(),
    /** 正文要求使用者提供密钥 / 令牌之类的凭据。 */
    val needsCredentials: Boolean = false
)

/**
 * 文件名与正文的关键词扫描。
 * 规则刻意放宽到宁可误报：这里只出一行提示，漏报的代价比误报大得多。
 */
internal object SkillSafetyScan {

    private val SCRIPT_EXTENSIONS = setOf(
        "sh", "bash", "zsh", "fish", "py", "js", "mjs", "cjs", "ts", "rb", "pl", "php",
        "ps1", "bat", "cmd", "lua"
    )

    private val CREDENTIAL_WORDS = Regex(
        "(?i)(api[-_ ]?key|access[-_ ]?token|api[-_ ]?token|bearer|secret|password|passwd|credential|密钥|令牌|凭据)"
    )

    fun scan(files: List<String>, skillMd: String?): SkillSafety = SkillSafety(
        scripts = files
            .filter { it.substringAfterLast('.', "").lowercase() in SCRIPT_EXTENSIONS }
            .sorted(),
        needsCredentials = skillMd != null && CREDENTIAL_WORDS.containsMatchIn(skillMd)
    )
}
