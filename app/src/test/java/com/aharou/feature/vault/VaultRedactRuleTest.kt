package com.aharou.feature.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `redact()` 的命中规则：只有「看起来像凭据」的值才替换，代码里的同名标识符不该被误伤。
 * 走纯函数 [redactText] / [VaultRepository.looksLikeCredential]，不需要 Android 环境。
 */
class VaultRedactRuleTest {

    @Test
    fun commonIdentifiersAreNotRedacted() {
        val text = "查到 dongwu 在 attached_equip 表里，HistoryPhase 是枚举值"
        val secrets = listOf(
            "db_user" to "dongwu",
            "equip_table" to "attached_equip",
            "phase" to "HistoryPhase",
        )
        assertEquals(text, redactText(text, secrets))
    }

    @Test
    fun longMixedValueIsRedacted() {
        val secret = "Zx9Qm2Lp7Rt4Wv1Bn8Kc5Hd3S"
        val out = redactText("key=$secret end", listOf("openai" to secret))
        assertEquals("key=[REDACTED:openai] end", out)
    }

    @Test
    fun midLengthValueWithUpperAndDigitIsRedacted() {
        val secret = "Abc12def9X"
        val out = redactText("token: $secret", listOf("api" to secret))
        assertEquals("token: [REDACTED:api]", out)
    }

    @Test
    fun midLengthValueWithSymbolAndUpperIsRedacted() {
        val secret = "Sk-abc!Def"
        assertTrue(VaultRepository.looksLikeCredential(secret))
        assertEquals("[REDACTED:api]", redactText(secret, listOf("api" to secret)))
    }

    @Test
    fun eightLowercaseCharsAreNotRedacted() {
        val secret = "abcdefgh"
        assertFalse(VaultRepository.looksLikeCredential(secret))
        assertEquals("v=$secret", redactText("v=$secret", listOf("api" to secret)))
    }

    @Test
    fun shortValuesAreNotRedacted() {
        assertFalse(VaultRepository.looksLikeCredential("abc123"))
        assertFalse(VaultRepository.looksLikeCredential("AB1"))
        assertFalse(VaultRepository.looksLikeCredential(""))
    }

    @Test
    fun lengthBoundariesFollowTheRule() {
        assertFalse(VaultRepository.looksLikeCredential("Abc12de"))             // 7 位：太短
        assertTrue(VaultRepository.looksLikeCredential("Abc12def"))              // 8 位：大写 + 数字
        assertFalse(VaultRepository.looksLikeCredential("abcdefghij12345678"))  // 18 位纯小写
        assertTrue(VaultRepository.looksLikeCredential("Zx9Qm2Lp7Rt4Wv1Bn8K"))   // 19 位
        // ≥20 一律脱敏：这么长的串按普通词误伤的几率极低。
        assertTrue(VaultRepository.looksLikeCredential("abcdefghijklmnopqrst"))
    }

    @Test
    fun camelCaseIdentifierWithDigitsIsRedacted() {
        // 固定规则本身的行为：驼峰标识符只要再带数字就落在「像凭据」这一侧。
        assertTrue(VaultRepository.looksLikeCredential("HistoryPhase2"))
        assertFalse(VaultRepository.looksLikeCredential("HistoryPhase"))
    }

    @Test
    fun longerValueIsReplacedBeforeContainedShorterOne() {
        val long = "Abc12def9Xqwertyuiop"
        val short = "Abc12def9X"
        assertTrue(long.contains(short))
        val out = redactText("k=$long", listOf("short" to short, "long" to long))
        assertEquals("k=[REDACTED:long]", out)
    }

    @Test
    fun everyOccurrenceIsReplaced() {
        val secret = "Zx9Qm2Lp7Rt4Wv1Bn8Kc5Hd3S"
        val out = redactText("$secret|$secret", listOf("openai" to secret))
        assertEquals("[REDACTED:openai]|[REDACTED:openai]", out)
    }

    @Test
    fun emptyInputsAreSafe() {
        assertEquals("", redactText("", listOf("api" to "Zx9Qm2Lp7Rt4Wv1Bn8Kc5Hd3S")))
        assertEquals("text", redactText("text", listOf("blank" to "")))
    }
}
