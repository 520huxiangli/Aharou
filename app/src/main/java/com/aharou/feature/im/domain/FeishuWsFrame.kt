package com.aharou.feature.im.domain

/**
 * 飞书 WebSocket 长连接的 protobuf 帧编解码（手写，不引第三方依赖）。
 *
 * 协议来自飞书官方 SDK 的 `pbbp2`：
 * ```
 * message PbHeader { string key = 1; string value = 2; }
 * message PbFrame {
 *   uint64 seq_id  = 1;   // 客户端维护、单调递增
 *   uint64 log_id  = 2;   // 服务端填充
 *   int32  service = 3;   // service_id，来自 WS URL 查询参数
 *   int32  method  = 4;   // 0=CONTROL（心跳） 1=DATA（事件）
 *   repeated PbHeader headers = 5;
 *   optional bytes payload = 8;  // 业务数据，本身是 JSON
 * }
 * ```
 *
 * 只实现需要的最小集合：varint 与 length-delimited 两种 wire type。
 */
internal object FeishuWsFrame {

    const val METHOD_CONTROL = 0
    const val METHOD_DATA = 1

    /** 一帧。headers 用键值对表示（`type=ping` / `type=pong` 等）。 */
    data class Frame(
        val seqId: Long = 0,
        val logId: Long = 0,
        val service: Int = 0,
        val method: Int = 0,
        val headers: Map<String, String> = emptyMap(),
        val payload: ByteArray? = null,
        /** 解码器实际走过的顶层字段号（诊断用）。 */
        val visited: List<Int> = emptyList(),
    )

    fun encode(frame: Frame): ByteArray {
        val out = ByteArrays()
        // SeqID / LogID / service / method 在 proto2 定义里是 required：
        // 即使值为 0 也要显式写出，否则严格实现的解码会判成缺字段。官方 SDK 是无条件写入的。
        out.writeVarintField(1, frame.seqId)
        out.writeVarintField(2, frame.logId)
        out.writeVarintField(3, frame.service.toLong())
        out.writeVarintField(4, frame.method.toLong())
        frame.headers.forEach { (key, value) ->
            val header = ByteArrays()
            header.writeStringField(1, key)
            header.writeStringField(2, value)
            out.writeBytesField(5, header.toByteArray())
        }
        frame.payload?.let { out.writeBytesField(8, it) }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): Frame {
        var seqId = 0L
        var logId = 0L
        var service = 0
        var method = 0
        val headers = mutableMapOf<String, String>()
        var payload: ByteArray? = null
        val visited = ArrayList<Int>()

        val reader = ByteArraysReader(bytes)
        while (reader.hasMore()) {
            val tag = reader.readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x7L).toInt()
            visited.add(field)
            when (field) {
                1 -> seqId = reader.readVarint()
                2 -> logId = reader.readVarint()
                3 -> service = reader.readVarint().toInt()
                4 -> method = reader.readVarint().toInt()
                5 -> {
                    val headerBytes = reader.readBytes()
                    var key = ""
                    var value = ""
                    val hr = ByteArraysReader(headerBytes)
                    while (hr.hasMore()) {
                        val hTag = hr.readVarint()
                        when ((hTag ushr 3).toInt()) {
                            1 -> key = hr.readString()
                            2 -> value = hr.readString()
                            else -> hr.skip((hTag and 0x7L).toInt())
                        }
                    }
                    if (key.isNotEmpty()) headers[key] = value
                }
                8 -> payload = reader.readBytes()
                else -> reader.skip(wire)
            }
        }
        return Frame(seqId, logId, service, method, headers, payload, visited)
    }

    /**
     * 只列出顶层字段序列（编号:类型:长度），不输出内容，用于诊断「事件帧解不出 payload」。
     *
     * 不能直接打 hex：日志层会把长 base64/hex 串脱敏成占位符，看不到字节。
     */
    fun describe(bytes: ByteArray): String {
        val out = StringBuilder()
        var pos = 0
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (pos < bytes.size && shift < 64) {
                val b = bytes[pos++].toInt() and 0xff
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return result
        }
        while (pos < bytes.size) {
            val tag = readVarint()
            val field = (tag ushr 3).toInt()
            val wire = (tag and 0x7L).toInt()
            if (out.isNotEmpty()) out.append(',')
            when (wire) {
                0 -> {
                    readVarint()
                    out.append("$field:v")
                }
                1 -> {
                    pos += 8
                    out.append("$field:f64")
                }
                2 -> {
                    val len = readVarint().toInt()
                    out.append("$field:L$len")
                    pos += len
                }
                5 -> {
                    pos += 4
                    out.append("$field:f32")
                }
                else -> {
                    out.append("$field:w$wire")
                    break
                }
            }
        }
        return out.toString()
    }

    /** 从 WSS 地址里取 `service_id` 查询参数，服务端用来路由。 */    fun serviceIdFrom(url: String): Int =
        Regex("[?&]service_id=(\\d+)").find(url)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

    /** 极简 protobuf 写入器。 */
    private class ByteArrays {
        private val buf = java.io.ByteArrayOutputStream()
        fun writeVarintField(field: Int, value: Long) {
            writeVarint((field shl 3).toLong())
            writeVarint(value)
        }
        fun writeStringField(field: Int, value: String) = writeBytesField(field, value.toByteArray())
        fun writeBytesField(field: Int, bytes: ByteArray) {
            writeVarint(((field shl 3) or 2).toLong())
            writeVarint(bytes.size.toLong())
            buf.write(bytes)
        }
        private fun writeVarint(v: Long) {
            var value = v
            while (true) {
                if ((value and 0x7FL.inv()) == 0L) {
                    buf.write(value.toInt())
                    return
                }
                buf.write(((value and 0x7F) or 0x80).toInt())
                value = value ushr 7
            }
        }
        fun toByteArray(): ByteArray = buf.toByteArray()
    }

    /** 极简 protobuf 读取器。 */
    private class ByteArraysReader(private val bytes: ByteArray) {
        private var pos = 0
        fun hasMore(): Boolean = pos < bytes.size
        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                val b = bytes[pos++].toInt() and 0xff
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return result
        }
        fun readBytes(): ByteArray {
            val len = readVarint().toInt()
            val out = bytes.copyOfRange(pos, pos + len)
            pos += len
            return out
        }
        fun readString(): String = String(readBytes(), Charsets.UTF_8)
        fun skip(wire: Int) {
            when (wire) {
                0 -> readVarint()
                1 -> pos += 8
                // 不能写成 pos += readVarint()：readVarint 自己会推进 pos，而 += 先取
                // pos 旧值再写回，长度字节会被吃回去，此后整个帧位置错位。
                2 -> {
                    val len = readVarint().toInt()
                    pos += len
                }
                5 -> pos += 4
                else -> pos = bytes.size
            }
        }
    }
}
