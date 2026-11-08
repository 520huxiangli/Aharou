package com.aharou.feature.agent.domain.container

import java.io.File

/**
 * 首次安装容器时写进 rootfs 的软件源内容。
 *
 * 抽成独立文件是因为 ContainerInstaller 已到行数上限；这里只做纯内容生成、架构判断与
 * 源文件写入，不读设置、不启动容器。
 */
internal object ContainerSourceFiles {

    /** 读 rootfs 的 `/etc/os-release` 字段；缺失或读失败返回空串。 */
    fun osReleaseField(rootfs: File, key: String): String =
        runCatching { File(rootfs, "etc/os-release").readLines() }
            .getOrNull()
            ?.firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')?.trim()?.trim('"')
            .orEmpty()

    /** 从动态链接器判断 guest 架构：Ubuntu 的 arm64 包只发布在 ports 仓。 */
    fun detectAptArch(rootfs: File): String = when {
        File(rootfs, "lib/ld-linux-aarch64.so.1").exists() -> "arm64"
        File(rootfs, "lib64/ld-linux-x86-64.so.2").exists() -> "amd64"
        File(rootfs, "lib/ld-linux-armhf.so.3").exists() -> "armhf"
        else -> ""
    }

    /** 生成国内 apt 源内容；不认识的发行版返回 null，保持镜像自带源。 */
    fun chinaAptSources(id: String, codename: String, arch: String, host: String): String? = when (id) {
        "debian" -> """
            Types: deb
            URIs: http://$host/debian
            Suites: $codename $codename-updates
            Components: main contrib

            Types: deb
            URIs: http://$host/debian-security
            Suites: $codename-security
            Components: main contrib
        """.trimIndent() + "\n"
        "ubuntu" -> {
            val repo = if (arch == "amd64" || arch == "i386") "ubuntu" else "ubuntu-ports"
            """
            Types: deb
            URIs: http://$host/$repo
            Suites: $codename $codename-updates $codename-backports $codename-security
            Components: main restricted universe multiverse
            """.trimIndent() + "\n"
        }
        else -> null
    }

    /** 按镜像自带的包管理器写国内源；返回是否真的写过。 */
    fun writeChinaMirror(rootfs: File, host: String, log: (String) -> Unit): Boolean {
        if (!File(rootfs, "etc/apk/repositories").isFile && File(rootfs, "etc/apt").isDirectory) {
            return writeAptMirror(rootfs, host, log)
        }
        val repositories = File(rootfs, "etc/apk/repositories")
        val branch = runCatching {
            if (repositories.isFile) Regex("alpine/([^/]+)/").find(repositories.readText())?.groupValues?.get(1) else null
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "v3.24"
        return runCatching {
            repositories.writeText("http://$host/alpine/$branch/main\nhttp://$host/alpine/$branch/community\n")
            log("首次安装已按设置换成国内源：$host（$branch）")
            true
        }.getOrElse { false }
    }

    private fun writeAptMirror(rootfs: File, host: String, log: (String) -> Unit): Boolean {
        val osRelease = runCatching { File(rootfs, "etc/os-release").readText() }.getOrNull() ?: return false
        fun field(key: String) = Regex("^$key=(.*)$", RegexOption.MULTILINE).find(osRelease)
            ?.groupValues?.get(1)?.trim()?.trim('"')
        val id = field("ID") ?: return false
        val codename = field("VERSION_CODENAME") ?: return false
        val content = chinaAptSources(id, codename, detectAptArch(rootfs), host) ?: return false
        // 旧源整体挪走：同目录留着 apt 会把旧官方源一起用上。
        val backup = File(rootfs, "etc/apt/mirror-backup").apply { mkdirs() }
        listOf(File(rootfs, "etc/apt/sources.list")).filter { it.isFile }.forEach {
            runCatching { it.copyTo(File(backup, it.name), overwrite = true); it.delete() }
        }
        File(rootfs, "etc/apt/sources.list.d").listFiles()?.forEach { file ->
            if (file.isFile) runCatching { file.copyTo(File(backup, file.name), overwrite = true); file.delete() }
        }
        return runCatching {
            val dir = File(rootfs, "etc/apt/sources.list.d").apply { mkdirs() }
            File(dir, "aharou-mirror.sources").writeText(content)
            log("首次安装已按设置换成国内 apt 源：$host（$id $codename）")
            true
        }.getOrElse { false }
    }
}
