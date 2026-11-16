package com.aharou.feature.im.data.napcat

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

internal object NapCatRootfs {
    /** 基座发行版标识：安装记录里存一份，升级时用它判断要不要重建 rootfs。 */
    const val VERSION = "ubuntu-24.04.3"

    fun requireArchitecture(architecture: String) {
        require(architecture in setOf("aarch64", "x86_64")) { "Unsupported NapCat rootfs architecture: $architecture" }
    }

    fun url(architecture: String): String {
        requireArchitecture(architecture)
        val arch = if (architecture == "aarch64") "arm64" else "amd64"
        return "https://mirrors.huaweicloud.com/ubuntu-cdimage/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-$arch.tar.gz"
    }

    fun sha256(architecture: String): String {
        requireArchitecture(architecture)
        return if (architecture == "aarch64")
            "7b2dced6dd56ad5e4a813fa25c8de307b655fdabc6ea9213175a92c48dabb048"
        else "6bc2cde3930ad088b3bb46fa45279e96d25bc3810f209850ecbe4722711874f9"
    }

    private fun isGzip(source: File): Boolean = source.inputStream().use { stream ->
        stream.read() == 0x1f && stream.read() == 0x8b
    }

    fun normalize(source: File, dest: File, preinstalled: Boolean = false) {
        val temporary = File(dest.parentFile, dest.name + ".part")
        try {
            source.inputStream().buffered().use { raw ->
                // Ubuntu Base 是 .tar.gz，proot-distro 包是 .tar.xz；按魔数判断，不依赖文件名。
                val input = if (preinstalled || isGzip(source)) GzipCompressorInputStream(raw) else XZCompressorInputStream(raw)
                TarArchiveInputStream(input).use { tar ->
                    GzipCompressorOutputStream(temporary.outputStream().buffered()).use { gzip ->
                        TarArchiveOutputStream(gzip).use { output ->
                            output.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                            // 预装归档契约是根层，不按首项猜目录，避免隐藏文件被误认成外包目录。
                            var prefix: String? = if (preinstalled) "" else null
                            var entry = tar.nextEntry
                            while (entry != null) {
                                val current = entry
                                val name = current.name.removePrefix("./")
                                if (prefix == null && name.isNotEmpty()) {
                                    prefix = if (name.substringBefore('/') in setOf("bin", "etc", "usr", "root", "dev", "proc", "sys", "tmp", "var", "lib", "lib64", "sbin", "home", "opt", "run", "mnt", "media", "srv", "boot")) "" else name.substringBefore('/') + "/"
                                }
                                val root = prefix.orEmpty()
                                // 外包目录自身的条目要跳过：否则名字被剥成空串，写出一个 "/" 条目。
                                if (name.isNotEmpty() && name != root && name != root.removeSuffix("/")) {
                                    check(root.isEmpty() || name.startsWith(root)) { "Unexpected rootfs archive layout" }
                                    current.name = name.removePrefix(root)
                                    if (current.isLink) current.linkName = current.linkName.removePrefix("./").removePrefix(root)
                                    output.putArchiveEntry(current)
                                    if (current.isFile) tar.copyTo(output)
                                    output.closeArchiveEntry()
                                }
                                entry = tar.nextEntry
                            }
                        }
                    }
                }
            }
            check(temporary.renameTo(dest)) { "Cannot commit Ubuntu archive" }
        } finally { temporary.delete() }
    }
}

internal object NapCatDependencies {
    val packages = listOf(
        "ca-certificates", "xvfb", "xauth", "dpkg", "unzip", "libnss3", "libatk1.0-0t64",
        "libatk-bridge2.0-0t64", "libcups2t64", "libdrm2", "libxkbcommon0", "libxcomposite1",
        "libxdamage1", "libxfixes3", "libxrandr2", "libgbm1", "libasound2t64", "libgtk-3-0t64", "libgssapi-krb5-2",
        "libnotify4", "libsecret-1-0", "libxss1", "libxtst6", "libatomic1", "fonts-noto-cjk",
        // MaiBot 需要 Python 3.12+ 与编译环境（quick_algo 等包要在本机编）
        "python3", "python3-venv", "python3-pip", "python3-dev", "build-essential", "git", "curl"
    )

    val probeScript = """
        for package in "${'$'}@"; do
            status=${'$'}(/usr/bin/dpkg-query -W -f='${'$'}{db:Status-Abbrev}' "${'$'}package") || exit 1
            [ "${'$'}status" = 'ii ' ] || exit 1
        done
        for executable in /bin/bash /usr/bin/dpkg-query /usr/bin/dpkg-deb /usr/bin/tar /usr/bin/unzip /usr/bin/Xvfb /usr/bin/xvfb-run /usr/bin/xauth; do
            [ -x "${'$'}executable" ] || exit 1
        done
    """.trimIndent()

    suspend fun isReady(command: suspend (String, List<String>) -> Unit): Boolean = try {
        // marker 只描述镜像来源，不能证明安装状态；旧环境也必须走同一份实际检查。
        command("/bin/bash", listOf("-c", probeScript, "napcat-dependencies") + packages)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        false
    }
}
