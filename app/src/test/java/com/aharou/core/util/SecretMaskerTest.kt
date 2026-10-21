package com.aharou.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注入期掩码：[SecretMasker] 只替换凭据形态，不碰 IP、域名与绝对路径。
 *
 * 后三条是防回归的重点——把项目事实一起掩掉，检索会直接残废。
 */
class SecretMaskerTest {

    @Test
    fun `sk 密钥被掩码且保留前缀与长度`() {
        val out = SecretMasker.mask("令牌 sk-abcdefghijklmnop123456 已配置")
        assertFalse(out.contains("abcdefghijklmnop123456"))
        assertTrue(out.contains("[已脱敏: sk-a…共 25 位]"))
        assertTrue(out.contains("已配置"))
    }

    @Test
    fun `Bearer 令牌只掩码后半段`() {
        val out = SecretMasker.mask("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9abcdef")
        assertFalse(out.contains("eyJhbGciOiJIUzI1NiJ9abcdef"))
        assertTrue(out.contains("Bearer [已脱敏:"))
    }

    @Test
    fun `赋值形式的密码被掩码`() {
        val out = SecretMasker.mask("配置 password=SuperSecret123 在文件里")
        assertFalse(out.contains("SuperSecret123"))
        assertTrue(out.contains("password=[已脱敏:"))
    }

    @Test
    fun `私钥块整段掩码`() {
        val key = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA\n-----END RSA PRIVATE KEY-----"
        val out = SecretMasker.mask("下面这把：\n$key\n结束")
        assertFalse(out.contains("MIIEowIBAAKCAQEA"))
        assertTrue(out.contains("[已脱敏: 私钥块"))
    }

    @Test
    fun `全角冒号的赋值也会被掩码`() {
        // 记忆正文用全角冒号写凭据是常态，半角正则盖不到
        val out = SecretMasker.mask("- Token：fyhFkIDYtw3mAwAf11c5adCssiF")
        assertFalse(out.contains("fyhFkIDYtw3mAwAf11c5adCssiF"))
        assertTrue(out.contains("Token：[已脱敏: fyhF…"))
    }

    @Test
    fun `中文关键词的赋值也会被掩码`() {
        val out = SecretMasker.mask("密码：Abcd1234efgh")
        assertFalse(out.contains("Abcd1234efgh"))
        assertTrue(out.contains("密码：[已脱敏: Abcd…"))
    }

    @Test
    fun `中文关键词后无值时不误伤`() {
        val text = "凭据与流程全在案：服务器密码与连接方式见 SECRETS.md"
        assertEquals(text, SecretMasker.mask(text))
    }

    @Test
    fun `IP 不被掩码`() {
        val text = "服务器 1.117.60.254 上的服务归小染"
        assertEquals(text, SecretMasker.mask(text))
    }

    @Test
    fun `域名不被掩码`() {
        val text = "仓库在 cnb.cool/huxiangli/aharou-memory"
        assertEquals(text, SecretMasker.mask(text))
    }

    @Test
    fun `绝对路径不被掩码`() {
        val text = "凭据文件 ~/.aharou/git-credentials 权限 600"
        assertEquals(text, SecretMasker.mask(text))
    }

    @Test
    fun `commit sha 不被误伤`() {
        val text = "提交 079fb0aeb643127a2e4420f0e0bd8f9f69bffb1f 已推送"
        assertEquals(text, SecretMasker.mask(text))
    }

    @Test
    fun `普通正文原样返回`() {
        val text = "记忆同步是运行时往仓库单向推，改镜像会被覆盖。"
        assertEquals(text, SecretMasker.mask(text))
    }
}
