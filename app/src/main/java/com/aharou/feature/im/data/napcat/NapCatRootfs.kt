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
    fun requireArchitecture(architecture: String) {
        require(architecture in setOf("aarch64", "x86_64")) { "Unsupported NapCat rootfs architecture: $architecture" }
    }

    fun url(architecture: String): String {
        requireArchitecture(architecture)
        return "https://github.com/termux/proot-distro/releases/download/v4.29.0/debian-trixie-$architecture-pd-v4.29.0.tar.xz"
    }

    fun sha256(architecture: String): String {
        requireArchitecture(architecture)
        return if (architecture == "aarch64")
            "3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918"
        else "4b8f33b80a10d734ff935e5934588572f860c0c38a68bf91db59af0580370716"
    }

    fun normalize(source: File, dest: File, preinstalled: Boolean = false) {
        val temporary = File(dest.parentFile, dest.name + ".part")
        try {
            source.inputStream().buffered().use { raw ->
                val input = if (preinstalled) GzipCompressorInputStream(raw) else XZCompressorInputStream(raw)
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
                                if (name.isNotEmpty() && name != root.removeSuffix("/")) {
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
            check(temporary.renameTo(dest)) { "Cannot commit Debian archive" }
        } finally { temporary.delete() }
    }
}

internal object NapCatDependencies {
    val packages = listOf(
        "ca-certificates", "xvfb", "xauth", "dpkg", "unzip", "libnss3", "libatk1.0-0t64",
        "libatk-bridge2.0-0t64", "libcups2t64", "libdrm2", "libxkbcommon0", "libxcomposite1",
        "libxdamage1", "libxfixes3", "libxrandr2", "libgbm1", "libasound2t64", "libgtk-3-0t64", "libgssapi-krb5-2",
        "libnotify4", "libsecret-1-0", "libxss1", "libxtst6", "libatomic1", "fonts-noto-cjk"
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
