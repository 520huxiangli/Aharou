package com.aharou.core.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出站脱敏与入站回填的端到端行为：掩得掉、还原得回、流式切碎也不丢字。
 *
 * 「还原后与原文逐字一致」是这套东西唯一算数的验收标准——少一个空格、丢一个引号，
 * 用户看到的就不是他自己写的话了。
 */
class OutboundMaskerTest {

    private val masker = OutboundMasker.create()

    @Test
    fun `sk 密钥被替换成占位符且可逐字还原`() {
        val text = "我的密钥是 sk-abcdefghijklmnop123456，别外传"
        val masked = masker.mask(text)
        assertFalse(masked.contains("sk-abcdefghijklmnop123456"))
        assertTrue(masked.contains("{{SECRET_"))
        assertTrue(masked.contains("我的密钥是"))
        assertEquals(text, masker.restore(masked))
    }

    @Test
    fun `各类凭据混在一段话里都能逐字还原`() {
        val text = buildString {
            appendLine("key: sk-ant-api03-AAAABBBBCCCCDDDD")
            appendLine("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9abcdefgh")
            appendLine("password=SuperSecret123")
            appendLine("令牌：fyhFkIDYtw3mAwAf11c5adCssiF")
            appendLine("PEM：$PEM")
            appendLine("联系 zhang.san+dev@example.com")
            appendLine("身份证 110101199003071233")
            appendLine("卡号 4111 1111 1111 1111")
        }
        val masked = masker.mask(text)
        listOf(
            "AAAABBBBCCCCDDDD",
            "eyJhbGciOiJIUzI1NiJ9abcdefgh",
            "SuperSecret123",
            "fyhFkIDYtw3mAwAf11c5adCssiF",
            "MIIEowIBAAKCAQEA",
            "zhang.san+dev@example.com",
            "110101199003071233",
            "4111 1111 1111 1111",
        ).forEach { assertFalse("疑似未脱敏：$it", masked.contains(it)) }
        assertEquals(text, masker.restore(masked))
    }

    @Test
    fun `同一真值多次出现得到同一占位符`() {
        val masked = masker.mask("a sk-abcdefghijklmnop123456 b sk-abcdefghijklmnop123456")
        val placeholders = PLACEHOLDER.findAll(masked).map { it.value }.toSet()
        assertEquals(1, placeholders.size)
        assertEquals(1, masker.maskedCount())
        assertEquals(2, PLACEHOLDER.findAll(masked).count())
    }

    @Test
    fun `赋值类只替换值保留标签`() {
        val masked = masker.mask("password=SuperSecret123")
        assertTrue(masked, masked.startsWith("password={{SECRET_"))
        assertEquals("password=SuperSecret123", masker.restore(masked))
    }

    @Test
    fun `带引号的赋值还原后连引号一起还原`() {
        val text = """client_secret="AbCdEf1234567890" 已写入"""
        val masked = masker.mask(text)
        assertFalse(masked.contains("AbCdEf1234567890"))
        assertTrue(masked, masked.startsWith("""client_secret="{{SECRET_"""))
        assertEquals(text, masker.restore(masked))
    }

    @Test
    fun `流式增量回填拼起来等于原文`() {
        val original = "你的令牌是 sk-abcdefghijklmnopqrstuvwxyz，请收好，用完就删。"
        val masked = masker.mask(original)
        val output = StringBuilder()
        for (piece in masked.chunked(2)) {
            output.append(masker.restore(piece))
        }
        output.append(masker.flush())
        assertEquals(original, output.toString())
        assertEquals("", masker.flush())
    }

    @Test
    fun `占位符大小写被改也能回填`() {
        val masked = masker.mask("k sk-abcdefghijklmnop123456")
        val placeholder = PLACEHOLDER.find(masked)!!.value
        val shouted = masked.replace(placeholder, placeholder.uppercase())
        assertEquals("k sk-abcdefghijklmnop123456", masker.restore(shouted))
    }

    @Test
    fun `未知占位符原样保留`() {
        val text = "前面 {{SECRET_deadbeef}} 后面"
        assertEquals(text, masker.restore(text))
        assertEquals("", masker.flush())
    }

    @Test
    fun `clear 之后不再回填`() {
        val masked = masker.mask("password=SuperSecret123")
        assertEquals("password=SuperSecret123", masker.restore(masked))
        assertTrue(masker.maskedCount() > 0)
        masker.clear()
        assertEquals(0, masker.maskedCount())
        assertEquals("", masker.flush())
        assertEquals(masked, masker.restore(masked))
    }

    @Test
    fun `maskedCount 统计的是不同真值`() {
        masker.mask("a@example.com")
        masker.mask("a@example.com")
        assertEquals(1, masker.maskedCount())
        masker.mask("b@example.com")
        assertEquals(2, masker.maskedCount())
    }

    @Test
    fun `空串不炸也不登记`() {
        assertEquals("", masker.mask(""))
        assertEquals("", masker.restore(""))
        assertEquals(0, masker.maskedCount())
    }

    @Test
    fun `并发登记不丢映射`() {
        val values = (0 until 40).map { "sk-concurrentvalue${it}abcdefghij" }
        val threads = values.chunked(10).map { chunk ->
            Thread { chunk.forEach { masker.mask("key $it") } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(values.size, masker.maskedCount())
        values.forEach { assertEquals("key $it", masker.restore(masker.mask("key $it"))) }
    }

    @Test
    fun `真值里的正则特殊字符不被解释`() {
        val text = "password=ab\$cd\$ef12"
        val masked = masker.mask(text)
        assertFalse(masked.contains("ab\$cd\$ef12"))
        assertEquals(text, masker.restore(masked))
    }

    @Test
    fun `未闭合的长文本不会触发灾难性回溯`() {
        val unterminated = "-----BEGIN RSA PRIVATE KEY-----\n" + "A".repeat(100_000)
        val started = System.nanoTime()
        assertEquals(unterminated, masker.mask(unterminated))
        masker.mask("a".repeat(100_000))
        masker.mask((0 until 100_000).joinToString("") { if (it % 2 == 0) "a" else "." })
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        // 修之前这一组要跑十几分钟（单个未闭合 PEM 200KB 就要 517 秒）
        assertTrue("耗时 ${elapsedMs}ms", elapsedMs < 5_000)
    }

    private companion object {
        const val PEM = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA\n-----END RSA PRIVATE KEY-----"
        val PLACEHOLDER = Regex("""\{\{SECRET_[0-9a-f]{8,16}\}\}""")
    }
}
