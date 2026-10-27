package com.aharou.feature.agent.domain.container

/**
 * 命令输出回给模型前的清洗：去掉 ANSI/OSC 转义与控制字符、滤掉已知噪音行、压缩空行。
 *
 * 为什么不在引擎层（[LinuxContainerEngine]）做：同一条通道还供 git diff 预览、文件内容读取等
 * **按行解析**的调用方使用，在那里吞行或压空行等于改动被解析的数据本身。清洗只发生在
 * 「文本要交给模型」这一步。
 *
 * 为什么不做二次截断：长度上限由 [BoundedOutput] 负责，这里再截一次会让模型看到的输出与
 * 实际执行结果对不上（明明还有内容却像是命令就此结束）。
 */
object ContainerOutputSanitizer {

    /** 单行 ANSI：CSI（颜色/光标）、OSC（标题等）、以及两字符 ESC 序列。 */
    private val ANSI_ESCAPE = Regex(
        "\u001B(?:\\[[0-9;?]*[ -/]*[@-~]|\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|[@-Z\\\\-_])"
    )

    /** 残留控制字符（保留 \t）：[String.lineSequence] 已按 \r / \n / \r\n 分行，进度条的 \r 刷新天然成了多行。 */
    private val CONTROL_CHARS = Regex("[\u0000-\u0008\u000B-\u001F\u007F]")

    /**
     * 已知噪音行。
     *
     * 只收「确定与命令结果无关」的几种：proot 在 Android 上对 /proc/self/fd/N 的告警是每次起步
     * 都会出现的例行提示，留一条真实的绑定点问题（别的 proot warning）比全抹掉更有用；百分比
     * 进度条在无 TTY 时仍会按 \r 分段落成整行（[BoundedLineReader] 按 \r 分行），整行只有百分比
     * 没有结果信息；纯盲文 spinner 行同理。
     */
    private val NOISE_LINES = listOf(
        Regex("""^proot warning: can't sanitize binding "/proc/self/fd/\d+".*$"""),
        Regex("""^Warning: CPU doesn't support 32-bit.*$"""),
        Regex("""^\s*\d{1,3}%\s*$"""),
        Regex("""^\s*\d{1,3}%\s*(?:\[|\||\().*$"""),
        Regex("""^\s*[\[\(|]\s*\d{1,3}%\s*[\]\)|].*$"""),
        Regex("""^\s*[\u2800-\u28FF\s]+$"""),
    )

    /**
     * 清洗 [text]：逐行剥转义、滤噪音行，连续空行压成一个，去掉首尾空行。
     * 空串/纯空白原样返回（调用方据此判断「没有输出」）。
     */
    fun sanitize(text: String): String {
        if (text.isBlank()) return text
        val sb = StringBuilder(text.length)
        var pendingBlank = false
        for (raw in text.lineSequence()) {
            val line = CONTROL_CHARS.replace(ANSI_ESCAPE.replace(raw, ""), "")
            if (line.isBlank()) {
                pendingBlank = true
                continue
            }
            if (NOISE_LINES.any { it.matches(line) }) continue
            if (pendingBlank && sb.isNotEmpty()) sb.append('\n')
            pendingBlank = false
            sb.append(line).append('\n')
        }
        return sb.toString().trimEnd('\n')
    }
}
