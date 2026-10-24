package com.aharou.feature.im.domain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 飞书 WebSocket 帧的手写 protobuf 编解码。
 *
 * 这块没有第三方库兜底：字段号写错、varint 读错一位、跳过未知字段时把长度字节吃回去，
 * 表现都是「连上了但收不到消息」，线上极难定位。所以用往返与逐字节断言把行为钉死。
 */
class FeishuWsFrameTest {

    @Test
    fun encodeDecode_roundTripsAllFields() {
        val frame = FeishuWsFrame.Frame(
            seqId = 42L,
            logId = 7L,
            service = 3,
            method = FeishuWsFrame.METHOD_DATA,
            headers = mapOf("type" to "event", "sum" to "1"),
            payload = "{\"k\":1}".toByteArray(),
        )

        val decoded = FeishuWsFrame.decode(FeishuWsFrame.encode(frame))

        assertEquals(42L, decoded.seqId)
        assertEquals(7L, decoded.logId)
        assertEquals(3, decoded.service)
        assertEquals(FeishuWsFrame.METHOD_DATA, decoded.method)
        assertEquals("event", decoded.headers["type"])
        assertEquals("1", decoded.headers["sum"])
        assertArrayEquals("{\"k\":1}".toByteArray(), decoded.payload)
    }

    /** proto2 的 required 字段即使为 0 也要显式写出，否则严格解码方会判成缺字段。 */
    @Test
    fun encode_alwaysWritesRequiredZeroFields() {
        val bytes = FeishuWsFrame.encode(FeishuWsFrame.Frame())

        // field 1/2/3/4，均为 varint(0)：tag 8/16/24/32，值 0
        assertArrayEquals(byteArrayOf(0x08, 0x00, 0x10, 0x00, 0x18, 0x00, 0x20, 0x00), bytes)
    }

    @Test
    fun decode_keepsHeaderWhoseValueIsEmpty() {
        // key 非空、value 为空：值为空的 header 属于合法内容（如 type 之后没有附带值）
        val header = byteArrayOf(0x0A, 0x01, 'k'.code.toByte(), 0x12, 0x00)
        val bytes = byteArrayOf(0x08, 0x01, 0x2A, header.size.toByte()) + header

        assertEquals("", FeishuWsFrame.decode(bytes).headers["k"])
    }

    @Test
    fun decode_skipsUnknownFieldsOfEveryWireType() {
        val bytes = byteArrayOf(
            // field 1 = 5
            0x08, 0x05,
            // field 9, wire 2（length-delimited）：长度 2 的无关负载
            0x4A, 0x02, 'a'.code.toByte(), 'b'.code.toByte(),
            // field 10, wire 1（64 位）：8 字节
            0x51, 1, 2, 3, 4, 5, 6, 7, 8,
            // field 11, wire 5（32 位）：4 字节
            0x5D, 1, 2, 3, 4,
            // field 12, wire 0：varint
            0x60, 0x7F,
            // 未知字段之后还能继续读：field 4 = method 1
            0x20, 0x01,
        )

        val decoded = FeishuWsFrame.decode(bytes)

        assertEquals(5L, decoded.seqId)
        assertEquals(FeishuWsFrame.METHOD_DATA, decoded.method)
        assertEquals(listOf(1, 9, 10, 11, 12, 4), decoded.visited)
    }

    @Test
    fun decode_readsMultiByteVarints() {
        val frame = FeishuWsFrame.Frame(seqId = 300_000L, logId = Long.MAX_VALUE / 2, service = 1024)

        val decoded = FeishuWsFrame.decode(FeishuWsFrame.encode(frame))

        assertEquals(300_000L, decoded.seqId)
        assertEquals(Long.MAX_VALUE / 2, decoded.logId)
        assertEquals(1024, decoded.service)
    }

    @Test
    fun encode_omitsPayloadWhenNull() {
        val withPayload = FeishuWsFrame.encode(FeishuWsFrame.Frame(seqId = 1, payload = null))
        val emptyPayload = FeishuWsFrame.encode(FeishuWsFrame.Frame(seqId = 1, payload = ByteArray(0)))

        assertTrue(withPayload.size < emptyPayload.size)
        assertEquals(null, FeishuWsFrame.decode(withPayload).payload)
        assertArrayEquals(ByteArray(0), FeishuWsFrame.decode(emptyPayload).payload)
    }

    @Test
    fun serviceIdFrom_readsQueryParameter() {
        assertEquals(123, FeishuWsFrame.serviceIdFrom("wss://host/ws?service_id=123&x=1"))
        assertEquals(7, FeishuWsFrame.serviceIdFrom("wss://host/ws?x=1&service_id=7"))
    }

    @Test
    fun serviceIdFrom_fallsBackToZero() {
        assertEquals(0, FeishuWsFrame.serviceIdFrom("wss://host/ws"))
        assertEquals(0, FeishuWsFrame.serviceIdFrom("wss://host/ws?service_id=abc"))
        assertEquals(0, FeishuWsFrame.serviceIdFrom("wss://host/ws?serviceman=5"))
    }

    /** 诊断串只给字段号与类型长度，不输出内容——否则会被日志脱敏成占位符，等于什么都没打。 */
    @Test
    fun describe_listsFieldNumbersAndWireKinds() {
        val bytes = FeishuWsFrame.encode(
            FeishuWsFrame.Frame(seqId = 1, logId = 2, service = 3, method = 4, headers = mapOf("a" to "b"))
        )

        assertEquals("1:v,2:v,3:v,4:v,5:L6", FeishuWsFrame.describe(bytes))
    }
}
