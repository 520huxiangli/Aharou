package com.aharou.feature.agent.domain.container

/**
 * seccomp 兼容探针脚本：真实目录操作——进 /root、打印工作目录、建一个临时目录再删掉。
 *
 * 每步失败都带独立标记：只看退出码非零分不出「cd 失败 / 磁盘满」和真正的 seccomp 失败，
 * 后者才允许把容器切到更慢的全量 ptrace 模式（误判会让本来正常的设备莫名其妙变慢）。
 */
private const val SECCOMP_PROBE_SCRIPT =
    "cd /root >/dev/null 2>&1 || { echo PROBE_FAIL_CD; exit 41; }; " +
        "pwd >/dev/null || { echo PROBE_FAIL_PWD; exit 42; }; " +
        "d=\$(mktemp -d) || { echo PROBE_FAIL_MKTEMP; exit 43; }; " +
        "rmdir \"\$d\" || { echo PROBE_FAIL_RMDIR; exit 44; }; " +
        "echo PROBE_OK"

/** 探针限时 10 秒：陌生 ROM 上卡住也不能拖住用户，超时即放弃（不算 seccomp 失败特征）。 */
private const val SECCOMP_PROBE_TIMEOUT_MS = 10_000L

/**
 * 在容器内跑一次 seccomp 探针，返回原始输出与退出码。[forceCompat] 决定本次是否追加
 * PROOT_NO_SECCOMP=1——只有「默认模式已确认是 seccomp 失败」的第二次探测才传 true。
 *
 * 放在引擎外的独立文件：引擎已顶到单文件行数门禁（1000 行），新逻辑按约定拆同包小文件，
 * 不去抬 `.architecture-baseline.json`。
 */
internal suspend fun LinuxContainerEngine.runSeccompProbe(
    profile: ContainerProfile,
    forceCompat: Boolean,
): CommandResult {
    val result = execCaptured(
        SECCOMP_PROBE_SCRIPT,
        projectPath = null,
        timeoutMs = SECCOMP_PROBE_TIMEOUT_MS,
        profile = profile,
        seccompOverride = forceCompat,
    )
    return CommandResult(result.output, result.exitCode, result.truncated)
}
