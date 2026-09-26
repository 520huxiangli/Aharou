package com.aharou.feature.agent.domain.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPairGenerator

/**
 * SSH 主机密钥校验逻辑：首次连接需确认（不自动放行）、确认后匹配放行、指纹变化拒绝、删除后重新要求确认、端口隔离。
 * 存储用内存实现（[SshHostKeyStore] 接口），不依赖 Android。
 */
class SshHostKeyVerifierTest {

    private class InMemorySshHostKeyStore : SshHostKeyStore {
        private val map = mutableMapOf<String, String>()
        override fun get(host: String, port: Int): String? = map["$host:$port"]
        override fun save(host: String, port: Int, fingerprint: String) {
            map["$host:$port"] = fingerprint
        }
        override fun remove(host: String, port: Int) {
            map.remove("$host:$port")
        }
        override fun entries(): Map<String, String> = map.toMap()
    }

    private fun rsaPublicKey(): java.security.PublicKey =
        KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair().public

    private fun pendingException(
        verifier: SshHostKeyVerifier,
        host: String,
        port: Int,
        key: java.security.PublicKey
    ): SshHostKeyPendingException = try {
        verifier.verify(host, port, key)
        fail("应抛出 SshHostKeyPendingException")
        error("unreachable")
    } catch (e: SshHostKeyPendingException) {
        e
    }

    @Test
    fun first_connection_requires_confirmation_and_does_not_save() {
        val store = InMemorySshHostKeyStore()
        val verifier = SshHostKeyVerifier(store)
        val e = pendingException(verifier, "example.com", 22, rsaPublicKey())
        assertTrue(!e.changed)
        assertEquals("example.com", e.host)
        assertEquals(22, e.port)
        // 未确认前不保存指纹
        assertTrue(store.get("example.com", 22) == null)
    }

    @Test
    fun consumePending_exposes_pending_details() {
        val verifier = SshHostKeyVerifier(InMemorySshHostKeyStore())
        val key = rsaPublicKey()
        try {
            verifier.verify("example.com", 22, key)
            fail("应抛出 SshHostKeyPendingException")
        } catch (e: SshHostKeyPendingException) {
            // expected
        }
        val pending = verifier.consumePending()
        assertEquals("example.com", pending?.host)
        assertEquals(22, pending?.port)
        assertTrue(pending?.fingerprint?.startsWith("SHA256:") == true)
        // 已消费，第二次取不到
        assertTrue(verifier.consumePending() == null)
    }

    @Test
    fun matching_fingerprint_after_confirmation_is_allowed() {
        val store = InMemorySshHostKeyStore()
        val verifier = SshHostKeyVerifier(store)
        val key = rsaPublicKey()
        store.save("example.com", 22, sshHostKeyFingerprint(key))
        assertTrue(verifier.verify("example.com", 22, key))
    }

    @Test
    fun changed_fingerprint_is_rejected_and_flagged() {
        val store = InMemorySshHostKeyStore()
        val verifier = SshHostKeyVerifier(store)
        store.save("example.com", 22, sshHostKeyFingerprint(rsaPublicKey()))
        val e = pendingException(verifier, "example.com", 22, rsaPublicKey())
        assertTrue(e.changed)
    }

    @Test
    fun removing_fingerprint_requires_confirmation_again() {
        val store = InMemorySshHostKeyStore()
        val verifier = SshHostKeyVerifier(store)
        val key = rsaPublicKey()
        store.save("example.com", 22, sshHostKeyFingerprint(key))
        assertTrue(verifier.verify("example.com", 22, key))
        store.remove("example.com", 22)
        val e = pendingException(verifier, "example.com", 22, key)
        assertTrue(!e.changed)
    }

    @Test
    fun different_port_is_treated_as_different_server() {
        val store = InMemorySshHostKeyStore()
        val verifier = SshHostKeyVerifier(store)
        val key = rsaPublicKey()
        store.save("example.com", 22, sshHostKeyFingerprint(key))
        val e = pendingException(verifier, "example.com", 2222, key)
        assertTrue(!e.changed)
    }

    @Test
    fun fingerprint_is_stable_and_key_specific() {
        val key = rsaPublicKey()
        val other = rsaPublicKey()
        assertEquals(sshHostKeyFingerprint(key), sshHostKeyFingerprint(key))
        assertNotEquals(sshHostKeyFingerprint(key), sshHostKeyFingerprint(other))
        assertTrue(sshHostKeyFingerprint(key).startsWith("SHA256:"))
    }
}
