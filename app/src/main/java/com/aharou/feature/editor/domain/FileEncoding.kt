package com.aharou.feature.editor.domain

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 文本文件的字符编码检测：把「字节 → 字符串」的编码歧义收敛到单一实现，
 * 供编辑器读文件、Git diff 读工作区文件统一使用。
 *
 * 检测规则（按优先级）：
 * 1. 带 UTF-8 / UTF-16 BOM → 按 BOM 解码（并剥掉 BOM）；
 * 2. 否则严格 UTF-8 解码（[CodingErrorAction.REPORT]）成功 → UTF-8；
 * 3. 仍失败 → 按 GBK 解码（GBK 是常见的中文 Windows 传统编码，覆盖绝大多数乱码场景）。
 *
 * 写侧约定：读入时记住检测到的 [FileEncoding.charset]，保存时用同一编码写回，
 * 避免「GBK 文件被静默转成 UTF-8」破坏原文件。
 */
enum class FileEncoding(val charset: Charset, val bom: ByteArray?) {
    UTF_8(Charsets.UTF_8, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())),
    UTF_16_LE(Charsets.UTF_16LE, byteArrayOf(0xFF.toByte(), 0xFE.toByte())),
    UTF_16_BE(Charsets.UTF_16BE, byteArrayOf(0xFE.toByte(), 0xFF.toByte())),
    GBK(Charset.forName("GBK"), null);

    /** 展示名，供状态栏等 UI 显示。 */
    val displayName: String
        get() = when (this) {
            UTF_8 -> "UTF-8"
            UTF_16_LE -> "UTF-16 LE"
            UTF_16_BE -> "UTF-16 BE"
            GBK -> "GBK"
        }

    companion object {
        /**
         * 检测字节流编码并解码为字符串。空输入返回 UTF-8 空串。
         */
        fun decode(bytes: ByteArray): DecodedText {
            if (bytes.isEmpty()) return DecodedText("", UTF_8)
            val withBom = bytes.mapBom()?.let { (enc, rest) -> enc to rest }
            val (enc, body) = withBom ?: (detectBomless(bytes) to bytes)
            val text = String(body, enc.charset)
            return DecodedText(text, enc)
        }

        private fun ByteArray.mapBom(): Pair<FileEncoding, ByteArray>? {
            for (enc in entries) {
                val bom = enc.bom ?: continue
                if (size >= bom.size && bom.indices.all { this[it] == bom[it] }) {
                    return enc to copyOfRange(bom.size, size)
                }
            }
            return null
        }

        /** 无 BOM 时：严格 UTF-8 成功即 UTF-8，否则 GBK。 */
        private fun detectBomless(bytes: ByteArray): FileEncoding {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                decoder.decode(ByteBuffer.wrap(bytes))
                UTF_8
            } catch (e: CharacterCodingException) {
                GBK
            }
        }
    }
}

/** 解码结果：字符串 + 检测到的编码（写回时用）。 */
data class DecodedText(val text: String, val encoding: FileEncoding)
