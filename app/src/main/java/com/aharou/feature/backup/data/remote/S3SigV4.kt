package com.aharou.feature.backup.data.remote

import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 手写 AWS Signature Version 4 签名（默认服务名 s3），不引 AWS SDK。
 *
 * 纯 JVM 实现（只用 java.security / javax.crypto / java.time），可被纯 JVM 单测直接驱动；
 * 已按 AWS 官方 aws-sig-v4-test-suite 的 get-vanilla / get-vanilla-query-order-key-case 向量校验。
 *
 * 易错点，实现里已专门处理：
 * - CanonicalHeaders 每条 `name:value\n`，末尾保留空行，再与 SignedHeaders 用 `\n` 拼接；
 * - 参与签名的 query 必须是已编码、且与实际请求 URL 完全一致的那份；
 * - Content-Type 要参与签名时，务必与实际发送的头一字不差。
 */
object S3SigV4 {

    const val ALGORITHM = "AWS4-HMAC-SHA256"
    const val SERVICE = "s3"

    /** 空载荷（GET/DELETE 等）的 SHA-256 十六进制。 */
    const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val SHA256_ALGORITHM = "SHA-256"
    private val HEX = "0123456789ABCDEF".toCharArray()

    private val AMZ_DATE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

    /** x-amz-date，形如 `20150830T123600Z`（UTC）。 */
    fun amzDate(epochMillis: Long): String = AMZ_DATE_FORMAT.format(Instant.ofEpochMilli(epochMillis))

    fun sha256Hex(data: ByteArray): String = hex(MessageDigest.getInstance(SHA256_ALGORITHM).digest(data))

    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[v shr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    /**
     * AWS URI 编码：仅 `A-Za-z0-9-_.~` 不编码；[encodeSlash] 为 false 时保留 `/`（用于路径），
     * 为 true 时把 `/` 编成 `%2F`（用于 query 的键与值）。
     */
    fun uriEncode(value: String, encodeSlash: Boolean): String {
        val sb = StringBuilder(value.length * 3 / 2)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            when {
                (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') ||
                    c == '-' || c == '_' || c == '.' || c == '~' -> sb.append(c)

                c == '/' && !encodeSlash -> sb.append(c)

                else -> {
                    val v = byte.toInt() and 0xFF
                    sb.append('%').append(HEX[v shr 4]).append(HEX[v and 0x0F])
                }
            }
        }
        return sb.toString()
    }

    fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    /** 派生签名密钥：kDate → kRegion → kService → kSigning。 */
    fun signingKey(secretKey: String, dateStamp: String, region: String, service: String): ByteArray {
        val kDate = hmac(("AWS4$secretKey").toByteArray(Charsets.UTF_8), dateStamp)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, service)
        return hmac(kService, "aws4_request")
    }

    /**
     * 生成 `Authorization` 头。
     *
     * @param headers 参与签名的头，key 必须全部小写；必须包含 `host` 与 `x-amz-date`。
     *   需要时调用方另加 `x-amz-content-sha256` / `x-amz-security-token` / `content-type`。
     * @param canonicalUri 已按 [uriEncode] 编码的路径（保留 `/`）。
     * @param canonicalQuery 已编码且按 key 升序排列的 query（无 `?`；无 query 传空串）。
     */
    fun sign(
        accessKey: String,
        secretKey: String,
        region: String,
        method: String,
        canonicalUri: String,
        canonicalQuery: String,
        headers: Map<String, String>,
        payloadHash: String,
        amzDate: String,
        service: String = SERVICE
    ): String {
        val sortedNames = headers.keys.sorted()
        val signedHeaders = sortedNames.joinToString(";")
        val canonicalHeaders = sortedNames.joinToString("") { "${it}:${headers.getValue(it).trim()}\n" }
        val canonicalRequest = listOf(
            method,
            canonicalUri,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            payloadHash
        ).joinToString("\n")

        val dateStamp = amzDate.substring(0, 8)
        val scope = "$dateStamp/$region/$service/aws4_request"
        val stringToSign = listOf(
            ALGORITHM,
            amzDate,
            scope,
            sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))
        ).joinToString("\n")

        val signature = hex(hmac(signingKey(secretKey, dateStamp, region, service), stringToSign))
        return "$ALGORITHM Credential=$accessKey/$scope, SignedHeaders=$signedHeaders, Signature=$signature"
    }
}
