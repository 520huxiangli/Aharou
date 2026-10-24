package com.aharou.feature.git.domain

import com.aharou.feature.agent.domain.container.CommandEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 提交前门禁：在仓库里跑自带的检查脚本（架构 / 迁移 / 技能）。
 *
 * 一次 shell 完成「探测 + 执行」，本地与远程模式走同一条路；只认固定的几个脚本名，
 * 不额外引入配置——仓库没放脚本时直接返回 [Result.noScript]。
 *
 * 单独成类而不是塞进 [GitRepository]：那个文件已经在行数上限边缘，门禁逻辑本身也和
 * 「读写 git」不是一类职责。
 */
@Singleton
class PreCommitChecker @Inject constructor(
    private val engine: CommandEngine,
) {

    /** 一次检查的结果：输出、是否通过、仓库里有没有脚本。 */
    data class Result(
        val output: String,
        val passed: Boolean,
        val noScript: Boolean,
    )

    suspend fun run(cwd: String, timeoutMs: Long = TIMEOUT_MS): Result {
        val command = buildString {
            append("found=0;")
            for (name in SCRIPTS) {
                append(" if [ -f scripts/")
                    .append(name)
                    .append(" ]; then found=1; echo '== scripts/")
                    .append(name)
                    .append("'; python3 scripts/")
                    .append(name)
                    .append(" 2>&1 || { echo '__FAILED__'; exit 1; }; fi;")
            }
            append(" [ \"\$found\" = 1 ] || echo '__NONE__'")
        }
        val result = engine.runCommandSyncWithExit(command, cwd, timeoutMs)
        val raw = result.output.trim()
        return Result(
            output = raw.replace("__FAILED__", "").replace("__NONE__", "").trim(),
            passed = result.exitCode == 0 && !raw.contains("__FAILED__"),
            noScript = raw.contains("__NONE__"),
        )
    }

    private companion object {
        /** 会尝试跑的脚本名（相对仓库根的 `scripts/`），存在才跑。 */
        val SCRIPTS = listOf(
            "check_architecture.py",
            "check_migrations.py",
            "check_skills.py",
        )

        /** 跑 Python 脚本可能几十秒，但不能无限挂着。 */
        const val TIMEOUT_MS = 300_000L
    }
}
