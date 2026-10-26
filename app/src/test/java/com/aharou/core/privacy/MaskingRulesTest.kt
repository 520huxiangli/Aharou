package com.aharou.core.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则边界：IP、域名、绝对路径、普通数字不能被当成个人信息。
 *
 * 这几条与 `core/util/SecretMasker.kt` 的口径一致（那边是记忆注入的单向掩码）：
 * 把项目事实一起掩掉，问「254 那台机器」得到的回复全是占位符，功能就废了。
 * 身份证与银行卡只认校验和，形状对但校验不过的是订单号和单号。
 */
class MaskingRulesTest {

    private val masker = OutboundMasker.create()

    @Test
    fun `IP 域名 绝对路径与普通数字不被误伤`() {
        val text = "服务器 1.117.60.254，仓库 cnb.cool/huxiangli/aharou-memory，" +
            "凭据文件 ~/.aharou/git-credentials，端口 8080，日志 1234ms，" +
            "提交 079fb0aeb643127a2e4420f0e0bd8f9f69bffb1f"
        assertEquals(text, masker.mask(text))
        assertEquals(0, masker.maskedCount())
    }

    @Test
    fun `身份证校验和不过时不掩码`() {
        val text = "单号 110101199003071234 已登记"
        assertEquals(text, masker.mask(text))
    }

    @Test
    fun `银行卡 Luhn 不过时不掩码`() {
        val text = "工单 4111 1111 1111 1112 与 4111111111111112 都待核对"
        assertEquals(text, masker.mask(text))
    }

    @Test
    fun `校验和通过的身份证与银行卡被掩码`() {
        val masked = masker.mask("身份证 110101199003071233，卡号 4111 1111 1111 1111")
        assertFalse(masked.contains("110101199003071233"))
        assertFalse(masked.contains("4111 1111 1111 1111"))
        assertEquals(2, masker.maskedCount())
    }

    @Test
    fun `校验和函数本身`() {
        assertTrue(chinaIdChecksumValid("110101199003071233"))
        assertFalse(chinaIdChecksumValid("110101199003071234"))
        assertFalse(chinaIdChecksumValid("11010119900307123"))
        assertTrue(luhnValid("4111111111111111"))
        assertTrue(luhnValid("4111 1111 1111 1111"))
        assertFalse(luhnValid("4111111111111112"))
        assertFalse(luhnValid("411111111111"))
    }

    @Test
    fun `未闭合的模板变量不会被当成半截占位符掐住`() {
        val text = "模板里写 {{ARGUMENTS 和 {{SECRET_zzz}}，都别动"
        assertEquals(text, masker.restore(text))
        assertEquals("", masker.flush())
    }

    @Test
    fun `半截占位符被掐住并由 flush 吐出`() {
        assertEquals("看这个 ", masker.restore("看这个 {{SEC"))
        assertEquals("{{SEC", masker.flush())
        assertEquals("", masker.flush())
    }

    @Test
    fun `规则可以按条禁用`() {
        val rules = MaskingRules.defaultRules().map {
            if (it.kind == MaskingKind.EMAIL) it.copy(enabled = false) else it
        }
        val custom = OutboundMasker.create(rules)
        val text = "联系 a@example.com"
        assertEquals(text, custom.mask(text))
    }

    @Test
    fun `过短的赋值不掩码以免误伤正文`() {
        val text = "password=x 与 token: 待填"
        assertEquals(text, masker.mask(text))
    }
}
