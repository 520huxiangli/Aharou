package com.aharou.core.util

/**
 * 解析 native 崩溃的 tombstone protobuf。
 *
 * [android.app.ApplicationExitInfo.getTraceInputStream] 在 `REASON_CRASH_NATIVE` 下返回的是
 * tombstone 的 protobuf（schema 见 AOSP `system/core/debuggerd/proto/tombstone.proto`），
 * 直接当文本读只会得到一堆乱码且丢失线程边界。这里按 wire-format 手写反序列化（不引入
 * protobuf 运行时依赖），渲染成接近 `debuggerd` 文本 tombstone 的可读形式，重点是崩溃线程
 * （`tid` 对应的 `Thread`）及其调用栈。
 */
object TombstoneParser {

    /** 单线程最多渲染的调用栈帧数（栈溢出时可达数千帧）。 */
    private const val MAX_FRAMES = 128

    /** 渲染文本长度上限，避免超大 tombstone 撑爆按天日志。 */
    private const val MAX_CHARS = 200_000

    /** 解析并渲染；格式不符或截断时返回 null。 */
    fun parse(bytes: ByteArray): String? = runCatching { Parser(bytes).render() }.getOrNull()

    private class Parser(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {

        private fun eof() = pos >= end

        private fun readVarint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                if (pos >= end) throw IllegalStateException("varint 越界")
                val b = buf[pos++].toInt() and 0xFF
                result = result or ((b.toLong() and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IllegalStateException("varint 过长")
            }
        }

        private fun readLen(): Int {
            val n = readVarint().toInt()
            if (n < 0 || pos + n > end) throw IllegalStateException("length-delimited 越界")
            return n
        }

        private fun skip(wire: Int) {
            when (wire) {
                0 -> readVarint()
                1 -> pos += 8
                2 -> {
                    // 不能写成 pos += readLen()：readLen() 内部已把 pos 前移过 varint 前缀，
                    // 而 pos += … 会先读旧值再相加，导致漏算前缀字节、后续字段整体错位。
                    val n = readLen()
                    pos += n
                }
                5 -> pos += 4
                else -> throw IllegalStateException("未知 wire type $wire")
            }
            if (pos > end) throw IllegalStateException("越界")
        }

        private fun readString(): String {
            val n = readLen()
            val s = String(buf, pos, n, Charsets.UTF_8)
            pos += n
            return s
        }

        /** 读取一个 length-delimited 子消息（共享底层 buffer，不做拷贝）。 */
        private fun sub(): Parser {
            val n = readLen()
            val s = Parser(buf, pos, pos + n)
            pos += n
            return s
        }

        /**
         * 遍历当前消息的字段。action 返回 true 表示已消费该字段，false 则由本函数按 wire type 跳过。
         * 传入 receiver 便于在 lambda 内调用读取原语。
         */
        private inline fun fields(action: (Parser, Int, Int) -> Boolean) {
            while (!eof()) {
                val tag = readVarint().toInt()
                val field = tag ushr 3
                val wire = tag and 7
                if (!action(this, field, wire)) skip(wire)
            }
        }

        fun render(): String {
            val t = parseTombstone()
            val sb = StringBuilder()
            sb.append("arch=").append(archName(t.arch))
            if (t.buildFingerprint.isNotEmpty()) sb.append(" fingerprint=").append(t.buildFingerprint)
            sb.append('\n')
            if (t.timestamp.isNotEmpty()) sb.append("timestamp=").append(t.timestamp).append('\n')
            sb.append("pid=").append(t.pid).append(" tid=").append(t.tid).append(" uid=").append(t.uid)
            if (t.selinuxLabel.isNotEmpty()) sb.append(" selinux=").append(t.selinuxLabel)
            sb.append('\n')
            if (t.commandLine.isNotEmpty()) sb.append("cmdline=").append(t.commandLine.joinToString(" ")).append('\n')
            t.signal?.let { s ->
                sb.append("signal=").append(s.number).append('(').append(s.name).append(')')
                sb.append(" code=").append(s.code).append('(').append(s.codeName).append(')')
                if (s.hasFaultAddress) sb.append(" fault_addr=0x").append(java.lang.Long.toHexString(s.faultAddress))
                sb.append('\n')
            }
            t.abortMessage?.let { sb.append("abort_message=").append(it).append('\n') }
            if (t.causes.isNotEmpty()) sb.append("causes=").append(t.causes.joinToString("; ")).append('\n')
            sb.append("threads=").append(t.threads.size).append('\n')

            val crash = t.threads[t.tid]
            if (crash != null) {
                sb.append("--- 崩溃线程 tid=").append(crash.id).append(" name=\"").append(crash.name).append("\" ---\n")
                appendFrames(sb, crash)
            } else {
                sb.append("--- 未找到 tid=").append(t.tid).append(" 对应的线程 ---\n")
            }

            val others = t.threads.values.filter { it.id != t.tid }
            if (others.isNotEmpty()) {
                sb.append("--- 其他线程（仅首帧）---\n")
                others.forEach { th ->
                    sb.append("  tid=").append(th.id).append(' ').append(th.name)
                    th.backtrace.firstOrNull()?.let { fr ->
                        sb.append("  @ ").append(frameSymbol(fr))
                    }
                    sb.append('\n')
                }
            }

            if (sb.length > MAX_CHARS) {
                sb.setLength(MAX_CHARS)
                sb.append("\n... (渲染文本超限已截断)")
            }
            return sb.toString().trimEnd()
        }

        private fun appendFrames(sb: StringBuilder, th: ThreadInfo) {
            val frames = th.backtrace
            frames.take(MAX_FRAMES).forEachIndexed { i, fr ->
                sb.append("  #").append(i.toString().padStart(2, '0')).append(" pc 0x")
                    .append(java.lang.Long.toHexString(if (fr.pc != 0L) fr.pc else fr.relPc))
                    .append(' ').append(frameSymbol(fr)).append('\n')
            }
            if (frames.size > MAX_FRAMES) {
                sb.append("  ... 省略 ").append(frames.size - MAX_FRAMES).append(" 帧\n")
            }
        }

        private fun frameSymbol(fr: Frame): String = buildString {
            append(fr.functionName.ifEmpty { "??" })
            if (fr.functionOffset != 0L) append("+0x").append(java.lang.Long.toHexString(fr.functionOffset))
            if (fr.fileName.isNotEmpty()) append(" (").append(fr.fileName).append(')')
            if (fr.buildId.isNotEmpty()) append(" [").append(fr.buildId).append(']')
        }

        private fun parseTombstone(): Tombstone {
            val t = Tombstone()
            fields { r, f, w ->
                when (f) {
                    1 -> if (w == 0) { t.arch = r.readVarint().toInt(); true } else false
                    2 -> if (w == 2) { t.buildFingerprint = r.readString(); true } else false
                    4 -> if (w == 2) { t.timestamp = r.readString(); true } else false
                    5 -> if (w == 0) { t.pid = r.readVarint().toInt(); true } else false
                    6 -> if (w == 0) { t.tid = r.readVarint().toInt(); true } else false
                    7 -> if (w == 0) { t.uid = r.readVarint().toInt(); true } else false
                    8 -> if (w == 2) { t.selinuxLabel = r.readString(); true } else false
                    9 -> if (w == 2) { t.commandLine += r.readString(); true } else false
                    10 -> if (w == 2) { t.signal = parseSignal(r.sub()); true } else false
                    14 -> if (w == 2) { t.abortMessage = r.readString(); true } else false
                    15 -> if (w == 2) { parseCause(r.sub())?.let { t.causes += it }; true } else false
                    16 -> if (w == 2) { parseThreadEntry(r.sub(), t.threads); true } else false
                    20 -> if (w == 0) { t.processUptime = r.readVarint().toInt(); true } else false
                    else -> false
                }
            }
            return t
        }

        /** `map<uint32, Thread> threads`：entry 是 { 1: key(uint32), 2: value(Thread) }。 */
        private fun parseThreadEntry(entry: Parser, out: LinkedHashMap<Int, ThreadInfo>) {
            var key = -1
            var value: ThreadInfo? = null
            entry.fields { r, f, w ->
                when (f) {
                    1 -> if (w == 0) { key = r.readVarint().toInt(); true } else false
                    2 -> if (w == 2) { value = parseThread(r.sub()); true } else false
                    else -> false
                }
            }
            if (value != null) out[key] = value!!
        }

        private fun parseThread(p: Parser): ThreadInfo {
            val th = ThreadInfo()
            p.fields { r, f, w ->
                when (f) {
                    1 -> if (w == 0) { th.id = r.readVarint().toInt(); true } else false
                    2 -> if (w == 2) { th.name = r.readString(); true } else false
                    4 -> if (w == 2) { th.backtrace += parseFrame(r.sub()); true } else false
                    else -> false
                }
            }
            return th
        }

        private fun parseFrame(p: Parser): Frame {
            val fr = Frame()
            p.fields { r, f, w ->
                when (f) {
                    1 -> if (w == 0) { fr.relPc = r.readVarint(); true } else false
                    2 -> if (w == 0) { fr.pc = r.readVarint(); true } else false
                    3 -> if (w == 0) { fr.sp = r.readVarint(); true } else false
                    4 -> if (w == 2) { fr.functionName = r.readString(); true } else false
                    5 -> if (w == 0) { fr.functionOffset = r.readVarint(); true } else false
                    6 -> if (w == 2) { fr.fileName = r.readString(); true } else false
                    8 -> if (w == 2) { fr.buildId = r.readString(); true } else false
                    else -> false
                }
            }
            return fr
        }

        private fun parseSignal(p: Parser): Signal {
            val s = Signal()
            p.fields { r, f, w ->
                when (f) {
                    1 -> if (w == 0) { s.number = r.readVarint().toInt(); true } else false
                    2 -> if (w == 2) { s.name = r.readString(); true } else false
                    3 -> if (w == 0) { s.code = r.readVarint().toInt(); true } else false
                    4 -> if (w == 2) { s.codeName = r.readString(); true } else false
                    8 -> if (w == 0) { s.hasFaultAddress = r.readVarint() != 0L; true } else false
                    9 -> if (w == 0) { s.faultAddress = r.readVarint(); true } else false
                    else -> false
                }
            }
            return s
        }

        private fun parseCause(p: Parser): String? {
            var human: String? = null
            p.fields { r, f, w ->
                when (f) {
                    1 -> if (w == 2) { human = r.readString(); true } else false
                    else -> false
                }
            }
            return human
        }

        private fun archName(arch: Int): String = when (arch) {
            0 -> "ARM32"
            1 -> "ARM64"
            2 -> "X86"
            3 -> "X86_64"
            4 -> "RISCV64"
            5 -> "NONE"
            else -> "UNKNOWN($arch)"
        }
    }

    private class Tombstone {
        var arch = 0
        var buildFingerprint = ""
        var timestamp = ""
        var pid = 0
        var tid = 0
        var uid = 0
        var selinuxLabel = ""
        var processUptime = 0
        val commandLine = mutableListOf<String>()
        var signal: Signal? = null
        var abortMessage: String? = null
        val causes = mutableListOf<String>()
        val threads = LinkedHashMap<Int, ThreadInfo>()
    }

    private class Signal {
        var number = 0
        var name = ""
        var code = 0
        var codeName = ""
        var hasFaultAddress = false
        var faultAddress = 0L
    }

    private class ThreadInfo {
        var id = 0
        var name = ""
        val backtrace = mutableListOf<Frame>()
    }

    private class Frame {
        var relPc = 0L
        var pc = 0L
        var sp = 0L
        var functionName = ""
        var functionOffset = 0L
        var fileName = ""
        var buildId = ""
    }
}
