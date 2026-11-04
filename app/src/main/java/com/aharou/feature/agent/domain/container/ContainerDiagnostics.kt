package com.aharou.feature.agent.domain.container

import com.aharou.core.util.FileLogger

/** 日志 tag 与原引擎保持一致，方便按老习惯 grep。 */
private const val TAG = "LinuxContainerEngine"

/**
 * dump 一次容器运行环境（rootfs / proot 二进制 / 初始化脚本 / 磁盘空间）到日志。
 *
 * 这些状态平时不打印，一旦用户报“容器起不来/终端一片空白”就只能靠猜。在初始化前后与终端
 * 会话静默时主动各 dump 一次，日志里就有了可定位的证据。同时带上 provision.sh 自己的执行日志
 * 尾部（由脚本写入 /root/.aicode/provision.log），用于判断初始化菜单到底有没有跑起来。
 *
 * 放在独立文件里只为给 [LinuxContainerEngine] 腾出单文件行数额度，逻辑与原来一致。
 */
internal fun dumpContainerDiagnostics(
    installer: ContainerInstaller,
    profile: ContainerProfile,
    reason: String
) {
    runCatching {
        val rootfs = installer.rootfsDirFor(profile)
        val provisioned = java.io.File(rootfs, ".provisioned")
        val provision = java.io.File(installer.aharouDir, "provision.sh")

        fun desc(f: java.io.File?): String = when {
            f == null -> "null"
            !f.exists() -> "缺失(${f.absolutePath})"
            f.isDirectory -> "目录(${f.absolutePath})"
            else -> "${f.absolutePath}[${f.length()}B,可执行=${f.canExecute()}]"
        }

        val space = runCatching { rootfs.usableSpace / (1024 * 1024) }.getOrDefault(-1L)
        val ptmx = java.io.File("/dev/ptmx")
        FileLogger.i(
            TAG,
            "容器诊断($reason)：profile=${profile.id} mode=${profile.mode} rootfs=${desc(rootfs)} " +
                "标记=${if (provisioned.isFile) provisioned.readText().trim() else "无"} " +
                "proot=${desc(installer.prootBin)} loader=${desc(installer.prootLoader)} " +
                "loader32=${desc(installer.prootLoader32)} provision.sh=${desc(provision)} " +
                "nativeLibDir=${installer.prootBin.parentFile?.absolutePath} 可用空间=${space}MB " +
                "/dev/ptmx(可读=${ptmx.canRead()},可写=${ptmx.canWrite()})"
        )
        val provisionLog = java.io.File(installer.aharouDir, "provision.log")
        if (provisionLog.isFile) {
            val tail = runCatching { provisionLog.readLines().takeLast(20).joinToString(" | ") }.getOrDefault("")
            FileLogger.i(TAG, "provision 日志尾部（$reason）：$tail")
        } else {
            FileLogger.i(TAG, "provision.log 不存在（初始化脚本从未执行）：${provisionLog.absolutePath}")
        }
    }.onFailure { FileLogger.w(TAG, "输出容器诊断失败", it) }
}
