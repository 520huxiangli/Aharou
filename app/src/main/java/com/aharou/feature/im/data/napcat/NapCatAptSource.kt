package com.aharou.feature.im.data.napcat

internal object NapCatAptSource {
    const val SOURCE_FILE = "apt/sources.list"
    const val LISTS_DIRECTORY = "apt/lists"

    // 首次安装尚未保证 CA 包存在；HTTP 引导沿用容器惯例，索引及包仍由 apt 验签。
    val sources = """
        deb http://mirrors.huaweicloud.com/debian trixie main contrib
        deb http://mirrors.huaweicloud.com/debian trixie-updates main contrib
        deb http://mirrors.huaweicloud.com/debian-security trixie-security main contrib
    """.trimIndent() + "\n"

    fun arguments(command: String, packages: List<String> = emptyList()): List<String> {
        require(command == "update" || command == "install")
        require(command == "install" || packages.isEmpty())
        // 只隔离本次受管 apt 调用，不覆盖镜像默认配置，也不混用旧官方源的索引缓存。
        val options = listOf(
            "-o", "Dir::Etc::sourcelist=/napcat/$SOURCE_FILE",
            "-o", "Dir::Etc::sourceparts=-",
            "-o", "Dir::State::lists=/napcat/$LISTS_DIRECTORY/",
            "-o", "Dir::Cache::pkgcache=",
            "-o", "Dir::Cache::srcpkgcache=",
            // PRoot 下默认双栈解析可能失败，而同环境 IPv4 解析及下载可用。
            "-o", "Acquire::ForceIPv4=true"
        )
        return options + if (command == "update") {
            listOf("update", "--error-on=any")
        } else {
            listOf("install", "-y", "--no-install-recommends") + packages
        }
    }
}
