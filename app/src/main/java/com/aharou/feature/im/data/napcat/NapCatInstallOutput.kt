package com.aharou.feature.im.data.napcat

import com.aharou.core.util.SecretMasker

internal class NapCatInstallOutput(private val onLine: (String) -> Unit) {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun append(line: String) {
        val clean = sanitize(line)
        if (clean.isBlank()) return
        lines.addLast(clean)
        while (lines.size > 20) lines.removeFirst()
        onLine(clean)
    }

    @Synchronized
    fun text(): String = lines.joinToString("; ").takeLast(1600)

    companion object {
        internal fun sanitize(line: String): String = SecretMasker.mask(
            line.replace(Regex("\\u001B\\[[0-9;]*[A-Za-z]"), "")
                .replace(Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE), "[url]")
                .replace(Regex("(?i)(?:bearer\\s+)[^\\s,;]+"), "[redacted]")
                .replace(Regex("(?i)(?:token|password|passwd|secret|credential|authorization|cookie|qrcode)[\\\"']?\\s*[:=]\\s*[\\\"']?[^\\s,;]+"), "[redacted]")
        ).take(600)
    }
}
