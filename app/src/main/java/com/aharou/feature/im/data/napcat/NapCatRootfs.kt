package com.aharou.feature.im.data.napcat

import java.io.File
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

internal object NapCatRootfs {
    fun url(architecture: String) = "https://github.com/termux/proot-distro/releases/download/v4.29.0/debian-trixie-$architecture-pd-v4.29.0.tar.xz"
    fun sha256(architecture: String) = if (architecture == "aarch64")
        "3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918"
    else "4b8f33b80a10d734ff935e5934588572f860c0c38a68bf91db59af0580370716"

    fun normalize(source: File, dest: File) {
        val temporary = File(dest.parentFile, dest.name + ".part")
        try {
            XZCompressorInputStream(source.inputStream().buffered()).use { input ->
                TarArchiveInputStream(input).use { tar ->
                    GzipCompressorOutputStream(temporary.outputStream().buffered()).use { gzip ->
                        TarArchiveOutputStream(gzip).use { output ->
                            output.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                            var prefix: String? = null
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
                                    if (current.isLink) current.linkName = current.linkName.removePrefix(root)
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
